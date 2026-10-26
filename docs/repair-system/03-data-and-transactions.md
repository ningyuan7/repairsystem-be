# 数据模型与事务一致性

本文件是完整逻辑设计。V001 身份/门店/邀请/审计/幂等迁移已实现；其他表仍是后续计划，执行状态见阶段交付记录。

## 1. 数据库与通用约定

- 新建独立开发库 `repair_system`，不在现有校园业务库直接改表或清数据。
- 使用 Flyway 版本化迁移，计划目录 `src/main/resources/db/repair/migration/`；只由 repair 运行配置启用。V001 保留现有 users/login_logs 所需列，以兼容已选复用 Mapper；不导入校园业务表。集成配置依据 [Spring Boot 3.2.4 官方迁移说明](https://docs.spring.io/spring-boot/docs/3.2.4/reference/html/howto.html#howto.data-initialization.migration-tool.flyway)，具体依赖在实施时核对。
- 新表 InnoDB、utf8mb4。业务 ID 使用 BIGINT UNSIGNED 自增，引用 users.id 的类型保持一致。所有 JSON 接口的业务 ID 都输出十进制字符串，防止前端精度损失。
- 可并发修改的聚合含 `version INT UNSIGNED NOT NULL DEFAULT 0`。新业务时间以 UTC `DATETIME(3)` 保存；JSON 为 UTC ISO 8601，页面按 Asia/Shanghai 展示。数据库连接、会话和迁移使用 UTC 配置，并专项验证现有 TIMESTAMP 用户列的兼容性。
- 状态用受校验的 VARCHAR，不接受任意字符串。字段长度、金额非负、收货累计不超量同时由 DTO、服务和适用的数据库约束保证。
- 关联实体保留外键及查询索引；历史实体不级联物理删除。禁用门店、用户、资产不是 DELETE 业务数据。

## 2. 表与关键字段

以下省略各表通用的 created_at/updated_at；事件、收货、审计等追加记录只有 created_at。

| 表 | 关键字段 | 约束/索引 |
| --- | --- | --- |
| users / login_logs | 沿用账号与登录日志字段 | 从当前 schema 提取；不自动迁移已有账号或权限 |
| repair_members | user_id PK/FK、role、status、display_name(64)、contact_phone(32)、version | role 为三个业务角色；status=ACTIVE/DISABLED；索引(role,status) |
| repair_member_stores | user_id、store_id | 复合 PK(user_id,store_id)，索引(store_id,user_id)；只给 STORE_MANAGER 建授权 |
| repair_invitations | id(UUID)、email(128)、role、store_ids JSON、token_hash(64)、expires_at、consumed_at、inviter_id、version | token_hash 唯一；role 限门店管理员/工程师；索引(email,expires_at)；原 token 不入库/日志 |
| repair_system_locks | lock_name PK | 预置 `hq-role-management` 行；涉及总部角色或启用状态变动时锁此行，串行检查最后一个总部账号 |
| repair_stores | id、code(32)、name(128)、region(64)、address(255)、status、version | code 唯一；status=ACTIVE/DISABLED；索引(region,status) |
| repair_categories | id、code、name、status、version | code 唯一；停用只阻止新提交 |
| repair_assets | id、store_id、asset_code(64)、name(128)、zone(128)、brand(128)、model(128)、serial_number(128)、lifecycle、health_status、source_purchase_item_id NULL、replaced_by_asset_id NULL、version | 唯一(store_id,asset_code)、唯一(source_purchase_item_id)；索引(store_id,lifecycle,health_status)；API 中 condition 对应 health_status，避免 SQL 保留字；替换来源一项只能生成一个新资产 |
| repair_tickets | id、ticket_no(32)、store_id、reporter_id、asset_id NULL、replacement_asset_id NULL、category_id、title(100)、description(2000)、facility_snapshot JSON、contact_name(64)、contact_phone(32)、priority、status、resolution_plan、procurement_required、assignee_id NULL、assigned_at NULL、accepted_at NULL、first_started_at NULL、completed_repair_at NULL、submitted_for_acceptance_at NULL、completed_at NULL、submitted_at、version | ticket_no 唯一，格式 `WX`+全局 id；索引(store_id,status,submitted_at,id)、(assignee_id,status,assigned_at,id)、(category_id,submitted_at)、(completed_at,id) |
| repair_active_assets | asset_id PK、ticket_id UNIQUE | 活动工单对资产唯一占位；终态释放；未知资产工单不插入此表 |
| repair_purchase_orders | id、ticket_id、status、order_number(128) NULL、supplier(128)、ordered_at NULL、currency、adjustment_amount DECIMAL(14,2)、confirmed_total DECIMAL(14,2)、received_at NULL、version | 唯一(ticket_id,active_slot)，active_slot 是非 CANCELLED 返回 1、否则 NULL 的生成列；索引(status,ordered_at) |
| repair_purchase_items | id、purchase_order_id、purpose、name(128)、brand(128) NULL、model(128) NULL、quantity INT、unit_price DECIMAL(12,2)、received_quantity INT、spec_confirmed、spec_confirmed_by NULL、spec_confirmed_at NULL、source_ocr_job_id NULL、source_candidate_index NULL | quantity 1..999，unit_price>=0，0<=received_quantity<=quantity；purpose=PART/REPLACEMENT；索引(purchase_order_id,id)；规格人工确认信息在下单前必须完整 |
| repair_material_receipts | id、purchase_order_id、receiver_id、received_at、note(500)、request_key | request_key 按提交者唯一；索引(purchase_order_id,received_at)；追加记录不允许改写累计值 |
| repair_material_receipt_items | receipt_id、purchase_item_id、quantity | PK(receipt_id,purchase_item_id)，quantity>0；明细必须属于同一采购单 |
| repair_ticket_events | id、ticket_id、ticket_version、action、from_status、to_status、actor_id、actor_role、visibility、note(1000)、payload JSON | UNIQUE(ticket_id,ticket_version)；索引(ticket_id,id)；visibility=PARTICIPANTS/HQ_ONLY；采购金额只进 HQ_ONLY 内容 |
| repair_attachments | id(UUID)、uploader_id、store_id NULL、ticket_id NULL、purchase_order_id NULL、purpose、storage_backend、object_key(512)、content_type、size、sha256、status、expires_at、bound_event_id NULL | 唯一(storage_backend,object_key)；索引(uploader_id,status,expires_at)、(ticket_id,purpose)；status=STAGED/BOUND/DELETING/DELETED/EXPIRED；AVATAR 无 store_id，其他用途必须有明确门店作用域 |
| repair_ocr_jobs | id(UUID)、purchase_order_id、source_attachment_id、requester_id、status、provider、attempts、next_run_at、lease_token NULL、lease_until NULL、result JSON NULL、error_code NULL、version | 索引(status,next_run_at)、(purchase_order_id,created_at)；结果只对总部读取 |
| repair_notifications | id、recipient_id、event_id、type、message、ticket_id、is_read、read_at | UNIQUE(recipient_id,event_id,type)，索引(recipient_id,is_read,id)；内容按接收角色生成，不下发采购隐私 |
| repair_delivery_jobs | id、notification_id、channel、status、attempts、next_run_at、lease_token NULL、lease_until NULL、last_error_code NULL | UNIQUE(notification_id,channel)，索引(status,next_run_at)；channel 一期仅 EMAIL |
| repair_sla_marks | id、ticket_id、kind、cycle_key、due_at、notified_at | UNIQUE(ticket_id,kind,cycle_key)；kind=REVIEW/ACCEPT/REPAIR/ACCEPTANCE；改派及再次验收有不同周期 key |
| repair_cost_confirmations | ticket_id PK、labor_amount DECIMAL(14,2)、other_amount DECIMAL(14,2)、note、confirmed_by、confirmed_at、version | 金额>=0；采购总额从有效采购单读取，不在此再重复输入 |
| repair_idempotency_records | id、user_id、operation(128)、idempotency_key(64)、request_hash、http_status、response_json、expires_at | UNIQUE(user_id,operation,idempotency_key)，索引(expires_at) |
| repair_audit_logs | id、actor_id、action、resource_type、resource_id、request_id、summary JSON | 索引(resource_type,resource_id,id)、(actor_id,id)；用于账号、授权、门店/台账及总部金额操作，不存密码、完整图片或 token |

repair_ocr_jobs.status 为 QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED；repair_delivery_jobs.status 为 QUEUED/RUNNING/SUCCEEDED/FAILED。采购草稿及截图修改生成 HQ_ONLY 事件；采购下单、到货、派单、维修、验收的参与者事件只包含脱敏业务摘要，金额变更明细进入总部审计。

过程记录、预约、结果说明、建议费用及实际替换序列号以类型化 event payload 保存，不额外维护一张可以任意覆盖的“最新备注表”。工单详情从最近相应事件投影，迁移时对 payload 版本（payloadVersion）显式记录；基础查询字段与统计时间仍保存在工单主表。

工单优先级一期为 NORMAL/URGENT，由门店建议、总部审核确定。resolution_plan=REPAIR/PART_REPAIR/REPLACEMENT；后两者要求采购，REPLACEMENT 采购单恰有一条 quantity=1 的替换设备项。其他明细为 PART。普通配件维修不能在验收时偷偷变成替换资产。

facility_snapshot 保存提交时的门店名称、设施名称、位置、原品牌/型号/序列号；不保存采购候选。资产台账后来修改不改写此快照。未知资产在 SUBMITTED 时由总部关联；关联动作形成事件，保留原始上报内容，生成新的设施识别快照版本。

## 3. 权限和查询约束

每个 repair 请求先核验 ACTIVE 成员。HQ_ADMIN 可读全部；STORE_MANAGER 用 repair_member_stores 过滤 store_id；ENGINEER 用 assignee_id 过滤工单。筛选出的目标不存在或不在范围内统一 404；目标可见但动作角色不允许返回 403。

角色与门店授权不只放进 JWT，也不以旧的 campus.admin-user-ids 作为工单权限来源。停用成员/角色变更时撤销刷新令牌，并让下一次业务请求重新检查数据库。已开始执行的事务按该请求进入时的授权处理，不承诺追杀已在执行的请求。

采购完整 DTO、OCR 结果、订单截图下载独立限制为总部。门店/工程师的材料摘要必须使用独立投影，不把价格字段取出后交给前端隐藏。对应工单事件也按 visibility 过滤或生成脱敏摘要；总数不泄露 HQ_ONLY 事件数量。

所有按门店的聚合先加权限范围再统计。关键词只作用于工单号、标题和设施名称，使用参数绑定与 LIKE 转义，不搜索其他用户联系方式。排序字段通过枚举映射白名单，禁止直接拼入用户输入。

## 4. 事务边界与锁顺序

Controller 负责验证 DTO、提取登录身份和调用 Service。状态变化、事件、活动资产占位、站内通知、邮件任务、幂等结果由同一个 MySQL 事务提交。OCR/OSS/SMTP 网络调用不放进这个业务事务。

统一顺序：涉及资产先按 assetId 升序锁资产，再锁工单，再锁采购单及明细，最后按 ID 升序锁附件。未关联资产的工单从工单开始；关联/替换时需提前确定全部资产锁集合。超时任务沿用此顺序，不反向锁工单后再等资产。死锁只做有限重试，不能无限循环。

修改已有业务聚合的命令携带 expectedVersion。先锁并读取当前聚合，再核对版本、角色、前置状态、采购/材料条件，执行条件更新并检查影响行数。版本不符返回 VERSION_CONFLICT，失败不留下半条事件或半次到货。新建操作、启动新任务及单向的通知已读标记不强制提供尚不存在的聚合版本，但仍执行相应幂等/唯一约束。

关键事务：

| 操作 | 必须一起成功/回滚 |
| --- | --- |
| 提交报修 | 工单创建、可选资产占位、设施故障状态、本人故障图绑定、SUBMIT 事件、总部通知、幂等结果 |
| 审核/采购下单 | 计划校验、采购金额/明细确认、工单状态、版本、脱敏采购进度事件、总部明细审计及参与者通知；公开给参与者的事件不含金额 |
| 收货 | 追加收货单/明细、采购行 received_quantity 条件增加、采购状态/版本、工单事件/版本、通知；任何明细超量则整次回滚 |
| 派单/改派 | 受派工程师有效性、工单状态/处理人、版本、事件、新处理人通知及旧处理人撤回通知 |
| 提交维修结果 | 状态 WAITING_ACCEPTANCE、结果/完工时间事件、结果图绑定、验收通知 |
| 验收 | 状态终结、设施恢复或替换新建/旧资产退役、活动占位释放、结果事件及通知；重复验收不能重复建新资产 |
| 费用确认 | 总部确认的两类金额、审计、version、工单 HQ_ONLY 事件；不更改采购已确认明细 |

accepted_at 和 first_started_at 记录首次接单/首次开工，已记录后不清空；assigned_at 为当前派单周期时间。最近一次接单/开工从事件中读取。改派后状态 ASSIGNED，但 first_started_at 保留用于累计维修耗时。

## 5. 重复提交与冲突

已登录的 repair 业务写操作携带 `Idempotency-Key`，包括收货、采购下单、OCR 启动、验收和角色变更。认证端点不套用此表；受邀激活通过邀请的一次性消费约束处理。key 使用 UUID，记录有效期 24 小时；operation 包含资源 ID 与动作。规范化 body（上传使用内容哈希）形成 request_hash。

同一用户、operation、key：同请求内容返回第一次成功的 HTTP 状态与响应；不同内容返回 IDEMPOTENCY_CONFLICT。同 key 重试先读取已提交结果，再检查 expectedVersion，避免把已经成功的重试误判成旧版本。

幂等记录同时保存 `auth_version`。权限变更后即使重新登录，也不能回放变更前的敏感响应；返回 `IDEMPOTENCY_CONTEXT_CHANGED`，防止旧总部会话的采购/邀请/全店范围响应通过幂等缓存绕过新权限。

实现不得只查 Redis 再写数据库。事务内预留唯一幂等记录并执行业务，最后写响应并提交；并发唯一键冲突需结束当前事务，再在新事务读取已提交结果或有限重试。失败整笔回滚，不遗留永久 IN_PROGRESS。OCR/邮件本身另按任务 ID 和租约令牌控制。

同资产同时被不同 key 报修时，由 repair_active_assets 的 asset_id 唯一约束仲裁，失败返回 ASSET_HAS_ACTIVE_TICKET。未知资产工单只做请求幂等，总部关联资产时再次检查占位；不凭相似描述自动合单。

## 6. 私有文件与任务一致性

图片先验证解码并存私有对象，再创建 STAGED 附件记录；业务提交时将附件绑定到所属工单/采购单。文件写入成功而数据库失败的孤儿对象进入受限清理任务；清理仅限 repair 前缀和已确认无引用对象，不能清空旧 Bucket。

暂存文件有效期 24 小时。绑定使用 `uploader_id + scope + status=STAGED + expires_at` 的条件更新，单个附件不可被另一工单或另一采购单重复使用。绑定后访问由当前业务权限决定；曾上传过图片不意味着永久拥有其访问权。

存储 key 示例：`repair/staging/{uploaderId}/{uuid}.png`，确认后由内部记录解析；不接受客户端传任意 objectKey/URL。订单和结果图不得接入旧匿名 `/api/v1/storage/resources/**` 路径。

本地存储适配器将文件放在配置的私有数据目录，API 只通过附件 ID 读取；不直接映射静态目录。OSS 读取通过服务鉴权后生成有效期 60 秒的 GET 签名。签名失效后重取；权限撤销无法收回已签发 URL 的剩余 60 秒，按此边界验收。

任务领取：原子更新 QUEUED/可重试任务为 RUNNING，并记录随机 lease_token 与 lease_until；释放数据库锁后调用外部服务；结果提交必须以任务 ID、状态、lease_token 做 CAS。过期任务可重新领取，旧 worker 不得覆盖新 worker 的结果。内存线程池只执行已持久化任务，不承担任务可靠性。

OCR 每次任务执行最多尝试 2 次（首次 + 1 次明确的临时错误重试）；其他错误人工重试。邮件首次失败后最多自动重试 5 次，间隔 1/5/15/30/60 分钟，仍失败转 FAILED，保留人工重投入口。SMTP 接受只表示服务端接受投递，不等于收件人收到；网络超时重试可能产生重复邮件，不承诺 SMTP 恰好一次。

## 7. 迁移与验证要求

计划迁移：V001 用户/登录日志/成员/门店/邀请/门店授权/总部治理锁/管理审计；V002 分类/设施；V003 工单/事件/附件/幂等/基础站内通知；V004 采购/收货/OCR/费用确认；V005 邮件投递任务/超时标记。这样账号审计和基础通知在最早使用它们的阶段就具备对应表。资产 source_purchase_item_id、工单 replacement_asset_id 等跨阶段外键按实际依赖在后续迁移补齐，不能先创建指向尚不存在表的约束。

实施调整：成员写操作在第一阶段已经使用幂等，因此 `repair_idempotency_records` 提前至 V001；V003 不再重复创建。`repair_members` 增加 `auth_version`，只在权限/启用状态改变时递增，普通资料更新不撤销会话。幂等响应使用 AES-GCM 加密后写 JSON 字符串，邀请表仅保存 token 的 SHA-256，不明文持久化激活凭证。当前加密密钥由本地 JWT 私钥和独立用途标识派生；轮换私钥会使原 24 小时幂等回放不可解密，需要在维护窗口处理或先引入独立可轮换的响应密钥。

开发库种子数据只含虚构门店、设施和测试账号，不复用参考仓库的账号、密码、联系方式。旧库与本机配置保持原样。迁移先在随机命名的隔离库执行并验证从空库升级及重复启动；不把测试库迁移指向现有业务库。

验收必须覆盖：两个请求抢同一资产、两个总部并发派单、重复收货、同 key 不同 body、旧 worker 迟到、重复验收替换资产、跨门店读写、门店停用后旧单完成、费用聚合避免 join 明细倍增。先分工单/采购聚合再连接汇总，不能直接 join 事件和多行采购后 SUM。
