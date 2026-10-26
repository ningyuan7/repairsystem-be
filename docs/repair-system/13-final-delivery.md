> 目录更新：前后端现已分为独立仓库。后端命令在当前仓库根目录执行，前端命令在 RepairSystem-frontend 根目录执行；本页其余部分保留阶段交付记录。当前入口见 [后端 README](../../README.md) 和 [拆分说明](../repository-split.md)。

# 最终交付与本地运行

2026-10-26。设计基线中的阶段 1–6 均已落地，当前入口是门店设施报修系统；新旧项目均未部署。

## 1. 可用业务

- 内部受邀账号、首次工作资料窗口、资料编辑、找回密码入口、三角色和多门店数据权限。
- 门店、报修分类与设施台账；已知或手填设施报修、私有图片、审核、派单、接单、预约、开工、维修记录、结果、验收及退回。
- 无需采购、配件维修、替换设备三条流程；采购草稿、多明细、订单截图、确认下单、取消历史、分批收货、材料门槛和总部费用确认。
- OCR 持久化任务、恢复与有限重试、原文和坐标、候选应用及人工规格确认；识别不会自动覆盖草稿或原设施。
- 站内通知、未读与已读、可选邮件任务、自然时长超时提醒、角色工作台、总部汇总和 CSV。

权限在后端实时校验。改派、停用和门店范围变化立即影响后续访问；采购图、价格、OCR 和费用只允许总部读取。替换验收创建一份新资产，保留原设施快照和旧型号。金额使用十进制定点数，所有标识以字符串传输。

## 2. 独立工程

后端在 `backend`，为 JDK 21、Spring Boot 3.2.4、MyBatis 3.0.3、MySQL 8、Redis、Flyway 9.22.3、Spring Security/JWT。前端在 `frontend`，为 React 18、TypeScript、Vite 5、Ant Design 5 与 Ant Design Icons，仅包含报修源码。

后端是单独的 Maven 工程，坐标 `com.gaofeng.repair:repair-system-backend`，只有 RepairApplication 入口，不需要 repair 或 legacy-campus profile，不保留旧业务源码与配置。产物为 `target/repair-system.jar`。复用的认证、用户、限流与图片清洗代码已复制为本项目源码，不引用原工程。

完整接口合同见 [openapi.json](openapi.json)：67 个路径、77 个操作、90 个 schema。权限与条件必填规则以 [接口文档](04-api-and-ui.md) 为准。Ant Design 公共包仍有大块提示，尚未做网络与大数据量性能验收。

## 3. 基础环境与启动

准备 JDK 21、MySQL 8、Redis、Node.js 和 npm。新数据库必须名为 `repair_*`。应用在 Flyway 执行前校验库名；不导入或覆盖原校园数据。

以下 SQL 供开发者在自己的数据库中执行，本次未对旧库执行这些操作：

```sql
CREATE DATABASE repair_system CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'repair'@'localhost' IDENTIFIED BY '替换为独立数据库密码';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
ON repair_system.* TO 'repair'@'localhost';
```

以下后端命令均在 `backend` 目录执行；先运行 `java scripts/GenerateKeys.java` 生成本项目自己的本地密钥，再配置 PowerShell 环境变量：

```powershell
$env:JAVA_HOME = '你的 JDK 21 目录'
$env:REPAIR_DB_USERNAME = 'repair'
$env:REPAIR_DB_PASSWORD = '独立数据库密码'
$env:REPAIR_REDIS_HOST = 'localhost'
$env:REPAIR_REDIS_PORT = '6379'
$env:REPAIR_REDIS_DATABASE = '8'
# 有密码时设置 REPAIR_REDIS_PASSWORD。
# 默认使用 localhost:3306/repair_system；其他地址配置 REPAIR_DB_URL。
```

数据库 URL 要保留 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`，连接初始化也会设置 UTC。`REPAIR_PORT` 默认 8080。生产配置、密钥和真实密码不提交仓库；运行配置和测试配置均不读取原项目文件。

JWT 默认读取 `config/keys/private.pem`、`config/keys/public.pem`。本项目自行生成密钥，不复制其他项目密钥；也可通过 REPAIR_JWT_PRIVATE_KEY / REPAIR_JWT_PUBLIC_KEY 指定 Spring Resource 路径。首次初始化总部时显式设置：

```powershell
$env:REPAIR_BOOTSTRAP_ENABLED = 'true'
$env:REPAIR_BOOTSTRAP_EMAIL = '你的内部邮箱'
$env:REPAIR_BOOTSTRAP_PASSWORD = '自行设置的强密码'
$env:REPAIR_BOOTSTRAP_NAME = '总部管理员姓名'
mvn spring-boot:run
```

没有默认账号密码；仅无有效总部成员时初始化，绝不覆盖已有账号。密码至少 8 位、包含字母与数字、UTF-8 最多 72 字节。成功后将 bootstrap 关闭并移除终端中的初始化密码；其他成员由总部邀请，邀请 72 小时有效。

前端在本项目 `frontend` 目录执行：

```powershell
npm ci
npm run dev
```

打开 `http://localhost:5173`。默认代理后端 8080，默认允许 localhost/127.0.0.1 的 5173 Origin；改端口需同步 Vite 代理和 REPAIR_CORS_ORIGINS。私有图片默认保存于后端 `data/repair-private`，可用 `--repair.storage.local-root=本地目录` 指定；目录不能作为静态资源公开。

打包运行：

```powershell
mvn verify
java -jar target/repair-system.jar
```

## 4. OCR、邮件与后台任务

默认 REPAIR_OCR_ENABLED=false、REPAIR_MAIL_ENABLED=false。没有 OCR 配置仍可完整手填采购；缺邮箱配置时找回密码给出明确提示，不产生验证码假成功。

实际 OCR 现支持 LOCAL / TENCENT 两种适配，本机优先使用本地 RapidOCR + ONNX Runtime CPU，模型服务在 services/ocr，回环地址 8601，无需云密钥。腾讯云 GeneralAccurateOCR 保留为可选服务，仅 TENCENT 模式需要云凭据。每账号默认分钟 5 次/日 100 次、最多两次自动尝试、人工确认与候选保存规则保持不变。配置、启动与真实联调结果见 [集成说明](../integrations.md)。真实订单识别准确率尚未专项验收。

邮箱配置：REPAIR_MAIL_HOST、REPAIR_MAIL_PORT、REPAIR_MAIL_USERNAME、REPAIR_MAIL_PASSWORD，按供应商设置 REPAIR_MAIL_AUTH、REPAIR_MAIL_SSL、REPAIR_MAIL_STARTTLS、REPAIR_MAIL_STARTTLS_REQUIRED；QQ 465 使用 SSL=true、STARTTLS=false。REPAIR_MAIL_ENABLED 只控制业务通知邮件；验证码在邮箱服务可用且用户主动请求时发送。投递最多首次加 5 次重试，总部可通过 `/delivery-jobs/failed` 和 `/{id}/retry` 查看及重投失败任务。已获用户授权进行本机 QQ SMTP 联调，结果见 [集成说明](../integrations.md)。可选邮件健康探针已关闭，未配置邮箱不会让基础健康检查失败。

REPAIR_WORKERS_ENABLED 默认 true；OCR、邮件、超时扫描和受限文件清理均使用持久化状态或周期唯一标记，测试中默认关闭自动调度并显式执行。超时小时数可通过 repair.sla 配置调整，不自动改变工单状态。

当前存储是本地私有文件，不提供匿名签名窗口；每次读取仍检查当前权限。未实现 OSS/对象存储适配，也没有依赖真实 Bucket。

## 5. 验收与重现

抽离前最终验收于 2026-10-26 14:13:32（Asia/Shanghai）完成：**71 项测试，失败 0、错误 0、跳过 0，BUILD SUCCESS**。同级前端生产构建通过；实际 `repair-system.jar` 启动后，健康检查和维修会话接口返回 200，旧校园业务接口返回 404。

抽离后在独立目录再次完整验收：2026-10-26 14:51:07，71 项全部通过，失败/错误/跳过均为 0；日志见 target/standalone-full-verification.log。

完整验证包括认证回归、真实 MySQL 事务/并发、Redis 原子限额与一次性验证码，以及五组真实 Chromium 流程。无需采购、配件维修、替换设备、OCR 候选和通知报表均使用真实本机 API；没有拦截业务接口返回假数据。

```powershell
mvn '-Drepair.integration=true' '-Drepair.browser=true' `
  '-Dprotection.integration=true' '-Drepair.packaged=true' verify
```

数据库测试使用本项目 `config/repair-test.yml` 或 REPAIR_TEST_* 环境变量；自动生成临时 JWT 密钥。仅创建/迁移/删除严格匹配 `repair_test_<UUID>` 的临时库；测试账号需有建库权限，不得使用生产凭据。Redis 使用第 15 库和随机短 TTL 测试键，不执行 FLUSHDB。详见 [独立项目说明](../standalone-project.md)。

首次运行带 repair.packaged 的验证前，先执行 `mvn -DskipTests package` 生成实际 jar；打包启动测试将使用同一个隔离数据库验证 jar 的健康检查、repair session 和旧接口拒绝访问。

浏览器需要预先构建同级前端 dist，并具备 Playwright 与匹配 Chromium。Node 默认从 PATH 查找，也可设 REPAIR_NODE 或 `-Drepair.node=...`。模块通过 REPAIR_PLAYWRIGHT_MODULE / `-Drepair.playwright.module=...` 指定；默认查找 `target/repair-browser-tools/node_modules/playwright`，不读取原项目工具目录。不再硬编码机器盘符或浏览器缓存路径。本次验收使用 Playwright 1.51.1；测试输出只写 target。

```powershell
# 可在隔离的开发工具目录安装本次验证版本
npm install --prefix target/repair-browser-tools --no-save playwright@1.51.1
node target/repair-browser-tools/node_modules/playwright/cli.js install chromium
```

独立项目验证输出：`target/standalone-full-verification.log`；JUnit XML：`target/surefire-reports`；前端构建：项目根目录 `frontend-build.log`。浏览器截图分别位于 `target/repair-browser`、`repair-workflow-browser`、`repair-procurement-browser`、`repair-ocr-browser`、`repair-report-browser`。

截图已检查中文排版、390px 手机宽度、表格滚动、表单、按钮与状态。截图测试会等待收货弹窗的有限动画完成，避免把过渡帧当作最终界面。

OpenAPI 可在打包后离线更新：`powershell -File scripts/generate-repair-openapi.ps1`。该命令仅读取本地编译类和 jar，不启动应用、不连接数据库。

## 6. 明确边界

没有部署、推送代码或实际购物。OCR 当前优先使用本地模型；本次 SMTP 单封联调邮件已由用户明确授权，历史未联调边界以 [最新集成记录](../integrations.md) 为准。没有迁移旧业务数据。多公司租户、采购退货/退款、库存、在线支付及 Agent 不在本期。

当前已做本地正确性验收，本次另行完成 OCR/SMTP 接入，结果以集成记录为准；仍未做真实订单准确率、对象存储、多实例网络故障或大规模性能压测。数据库并发和租约故障通过真实 MySQL 测试验证，不能替代实际基础设施验收。

幂等响应当前以 JWT 私钥派生的独立用途密钥做 AES-GCM 加密；更换私钥会影响 24 小时内旧请求回放，需维护安排或后续引入独立可轮换密钥。SMTP 超时重试可能重复投递；OCR 只保守提取明确标签，复杂订单可继续手填。
