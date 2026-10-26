# 工程师查询缓存与 Canal 增量预热

适用业务：总部派单，工程师读取自己的受派工单。用户已确认列表和数量允许约 1–3 秒的展示延迟。接单、开工、改派、采购和权限校验仍以数据库为准，没有改为抢单模式。

## 本次实现范围

| 读取 | 处理 |
| --- | --- |
| 工程师工作台状态数量、前 10 条待办 | Redis 缓存 |
| 工程师工单列表：第 1 页、每页 20 条，可按状态筛选 | Redis 缓存；带关键词、门店、日期、优先级筛选时仍走原查询 |
| 工程师 SLA 超时数、未读通知数 | 实时数据库查询，避免无 binlog 事件时跨越时间阈值却不更新 |
| 详情、操作与 HQ/门店查询 | 原数据库实现 |

首次连接扫描有效工程师（每批 100 人），预热工作台、全部工单首页、待接单首页；后续收到变更后立即使受影响缓存失效，并在后台重新预热。其他状态首页按需首次构建。每 60 秒巡检有效工程师，弥补进程退出或预热队列丢失。预热只有一个工作线程，不占用 OCR/邮件调度线程；首次扫描完成时间取决于工程师数量，并非启动瞬间全量完成。

```mermaid
flowchart LR
  DB[MySQL ROW/FULL binlog] --> Canal[Canal 1.1.8 TCP]
  Canal --> Epoch[更新工程师缓存代次]
  Epoch --> Warm[合并预热请求]
  Warm --> Redis[Redis 工程师读取缓存]
  Redis --> Read[工程师列表与状态统计]
  DB --> Auth[实时成员与受派关系校验]
  Auth --> Read
```

## 一致性与权限

- key 包含数据库 URL 摘要、格式版本、工程师 ID、成员权限版本和有界查询条件；缓存内容携带全局/个人代次并在读取和发布时核对；不含明文关键词、联系方式或密钥。
- `repair_tickets` 的 before/after 受派人都失效，覆盖首次派单、撤回和改派。采购订单、商品明细、到货、费用变更按工单/订单定位当前工程师；收货事务也会更新商品的 `received_quantity`。门店、分类、成员资料变更使全局代次变化。
- 仅写入新的代次，不对 binlog 事件中的旧数据直接拼接缓存。重放事件可以重复失效，最终重新读取最新数据库；失效与入队成功后才 ACK，失败 rollback 后重试。服务重连时全局失效并重新扫描。
- 每次请求实时校验成员有效状态；缓存返回前按主键批量核验列表中所有工单仍归当前工程师。改派后旧工程师的缓存不能继续返回原工单。工单详情及所有操作维持原实时校验、锁和 expectedVersion 规则。
- `list`/`dashboard` 的读取入口不再包裹长时间的可重复读事务，避免已打开的旧快照把旧数据发布到新缓存代次。业务写事务未改。

## 击穿、空结果与故障

- 相同 key 使用 Redis SET NX、15 秒租约，等待者最多等 800 毫秒；不同时一起回源。Lua 校验锁 token 与两级代次后才能发布，防止重建过程中失效后又写回旧值；解锁也比对 token。
- 失效后复用固定的查询 key，以内容中的代次判断是否有效，避免高频派单产生大量历史代次数据 key、在长 TTL 内持续占用内存。
- 空列表和零计数同样缓存；常用查询 key 有界，不为任意关键词建立缓存。本次没有引入布隆过滤器，也没有给任意工单 ID 查询增加缓存。
- 物理 TTL 为 10–12 分钟随机抖动；长 TTL 只有在 CDC 新鲜时才能使用，不能把 TTL 当作一致性保障。
- V006 心跳表每秒写入一次时间戳，并经过 MySQL → Canal → 消费端。心跳年龄超过 3 秒时，不再沿用长 TTL；仅接受生成时间在 2 秒内的数据，否则受控回源。因此 Canal TCP 连着、但解析停滞时不会一直返回旧缓存。
- 每个应用实例最多同时执行 8 个缓存重建/故障回源，Redis 异常有 1 秒退避；超额请求返回 `503 READ_CACHE_BUSY`、`Retry-After: 1`，避免无限排队压垮连接池。实时权限查询及未缓存的复杂查询仍会使用数据库，缓存不能替代整体容量规划。
- 消费循环核对 Redis `INFO server` 的 `run_id`；Redis 进程重启时更换全局代次并清除新鲜标记，避免恢复快照复活旧缓存。Redis ACL 需允许 `INFO server`，否则停用 CDC 新鲜标记并采用短缓存回源。
- CDC 消费者通过 Redis 15 秒租约选主并持续续期。多实例部署需要保持同一数据库/Redis 命名空间，并在正式上线前进一步验证实例故障切换、网络分区、复制延迟与持久化恢复。本次只完成单机真实联调，不宣称生产 HA 验收完成。

V007 增加工程师最近工单、状态最近工单、有效工程师扫描索引，保持原排序，不改变工单状态机。

## 本地运行

1. MySQL 需要 `log_bin=ON`、`binlog_format=ROW`、`binlog_row_image=FULL`。复制账号只授予 REPLICATION SLAVE / REPLICATION CLIENT 及本项目库的 SELECT；应用仍使用自己的业务账号。
2. `config/local-canal.json` 保存独立复制账号，结构为 `REPAIR_CDC_DB_USER`（格式 `repair_cdc_` 加 8 位十六进制）与 `REPAIR_CDC_DB_PASSWORD`（24–30 位随机十六进制）。不提交该文件。Canal/MySQL 注册副本对 report-password 长度有限制，本机使用 24 字符随机值；不复用管理员密码。
3. 下载官方 `canal.deployer-1.1.8`，在 backend 执行：

```powershell
$env:JAVA_HOME='你的 JDK 21 目录'
./scripts/initialize-canal.ps1 -DistributionPath '解压后的 canal.deployer-1.1.8'
./scripts/start-canal.ps1
```

初始化只复制发行包 lib 和 Spring 模板；自己的配置及游标保存在忽略提交的 `target/canal-server`。重复初始化保留游标；禁止在服务运行时初始化。TCP 11112、管理端口 11115 仅监听回环地址；关闭默认全网卡指标端口。日志关闭会输出复制密码的 MysqlConnection INFO 项。复制账号开通、端口、数据库地址和唯一 slaveId 按实际部署环境调整，脚本针对本机 MySQL 3306。

4. 在进程环境或本机 `config/local-integrations.json` 设置 `REPAIR_READ_CACHE_ENABLED=true`、`REPAIR_CANAL_ENABLED=true`、`REPAIR_CANAL_DESTINATION=repair`。`scripts/start-local.ps1` 自动复用/启动本项目 Canal，再启动后端。源码默认关闭这两项，未接入 Canal 的环境可继续使用原读取方式。
5. 后端运行后执行 `scripts/check-read-cache.ps1`，检查 `target/read-cache-status.json`：`enabled=true` 且 `cdcFresh=true` 表示完整 binlog 心跳新鲜；仅进程/端口存活不能算同步健康。该检查临时进程关闭自己的消费者，不抢占线上 destination，也不发送邮件。

binlog 被清理、游标不存在时保持报错，不自动跳到最新位置。先暂停消费者并排查，确认恢复起点，再执行全局失效与预热；不要直接删除游标掩盖数据丢失。缓存格式升级修改版本前缀，历史 data key 自然到期。停用整个功能时将两项开关均关闭。

## 验证

新增 `RepairReadCacheIntegrationTest`（真实 Redis/MySQL、32 并发冷读、代次防回写、空结果、用户隔离、心跳失效、可见性和重建并发上限）与 `RepairCanalIntegrationTest`（真实 binlog、改派即时隔离、实时命令校验、HTTP 热读、到货与开工约束）。测试仅创建随机 `repair_test_*` 库，不向本机业务库插入示例工单，不执行 FLUSHDB。

普通集成测试启用 `-Drepair.integration=true`；真实 Canal 组额外启用 `-Drepair.canal.integration=true`，需要 `initialize-canal.ps1 -EnableTestDestination`，并授予专用复制账号 SELECT 到测试库命名空间。使用与开发实例分开的 `repair-test` destination，避免同 destination 客户端互相抢游标。

本机首轮实测：32 并发缓存冷读合并为 1 次数据库加载；派单显示约 750 毫秒；32 个同一工程师的热缓存 HTTP 请求无错误、无重型读取重建，P95 41 毫秒。HTTP 测试包含 JWT、实时权限及 SLA/通知数据库查询。这些是本机小样本验证，不代表数千工程师或百万工单的吞吐承诺。后续应使用代表性数据量、多个工程师分布以及实际硬件持续压测，关注连接池等待、数据库执行计划、Canal 心跳年龄、重建次数和 503 比例，再决定读副本、独立聚合表或分区归档。

机器可读证据：`target/cache-burst-result.json`、`cache-http-burst-result.json`、`canal-live-result.json`；完整回归日志 `canal-full-regression.log`。

官方依据：[Canal 1.1.8](https://github.com/alibaba/canal/releases/tag/canal-1.1.8)、[客户端 ACK/rollback 示例](https://github.com/alibaba/canal/wiki/ClientExample)。

## 最终验收记录

2026-10-26 19:29:36（Asia/Shanghai），最终版本 **92 项测试全部通过：失败 0、错误 0、跳过 0**，包含既有业务、真实 MySQL/Redis、浏览器流程、可执行 jar 与新增 13 项 Canal/缓存专项。运行 jar 与构建中的缓存类字节一致。

最终一轮：32 并发冷读数据库加载 1 次；同一工程师 32 个热缓存 HTTP 请求 P95 63 毫秒、错误 0、重型读取重建 0；真实派单展示 875 毫秒。高频失效 25 次保持 1 个数据 key。这里仍是本机小样本，不能据此推算生产容量。

本机已启用，数据库升级到 V007；前端 5173、后端 8080、OCR 8601 正常，完整 Canal 心跳检查 `cdcFresh=true`。结果归档 `target/canal-final-summary.json`。
