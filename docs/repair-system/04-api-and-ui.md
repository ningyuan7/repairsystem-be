# 接口与前端页面契约

本合同是完整目标 API；第一阶段已实现的接口以 [OpenAPI](openapi-phase1.yaml) 和 [交付记录](08-phase1-delivery.md) 为准。后续工单、采购、OCR 等仍待开发。

## 1. 通用规则

- 业务 API 前缀 `/api/v1/repair`；原登录、刷新、找回密码沿用 `/api/v1/auth`，按 repair 模式限制成员身份。Bearer access token，refresh token 不得访问业务 API。
- 所有业务 ID 为字符串；version 为整数；金额为两位小数十进制字符串；时间为 UTC ISO 8601；查询时间区间为 `[from,to)`。
- 成功直接返回 DTO，沿用现有项目习惯，不再套一层不同的 code/data 包装。错误 `{code,message,requestId}`；不得向客户端返回内部堆栈或 SQL。
- 已登录的 repair 业务写操作必须带 `Idempotency-Key: UUID`。认证端点沿用各自的 token/验证码规则；邀请激活用邀请的一次性消费约束，不要求匿名用户提供 userId 进行幂等。删除类行为使用有语义的动作，不用 GET 修改状态。开启相应 CORS 请求头，响应 `X-Request-Id`。
- 工单动作 body 的 `expectedVersion` 指工单版本；采购单动作同时带 `expectedVersion`（采购单）和 `expectedTicketVersion`（工单）；新建采购单只有 expectedTicketVersion。收货校验两个版本。
- 新建成功 201；普通命令 200；启动 OCR 202；幂等重试返回原成功状态与响应。状态变化响应含 ticketId、ticketNo、status、version；涉及采购另返回 purchaseOrderId、purchaseOrderVersion。
- 列表页 `page` 从 1 开始，`size` 默认 20、上限 100；固定排序 `createdAt DESC,id DESC`，工单按 `submittedAt DESC,id DESC`。返回 `{items,page,size,total}`。日期、状态枚举和 storeId 在服务端校验。
- 目标不在当前数据范围内返回 404；可见目标上的动作不允许返回 403；未登录/无效 token 返回 401；被停用成员返回 403 MEMBER_DISABLED。

## 2. 账号与会话

| 方法与路径 | 权限/请求要点 | 响应/行为 |
| --- | --- | --- |
| POST `/api/v1/auth/login` | 内部 ACTIVE 成员，沿用密码/验证码 DTO | AuthResponse；不允许旧校园账号绕过邀请直接获得业务权限 |
| POST `/api/v1/auth/token/refresh` | 有效 refresh token + ACTIVE 成员 | 原 TokenResponse，轮换并检查成员状态 |
| POST `/api/v1/auth/logout` | 沿用刷新令牌撤销 | 204 |
| POST `/api/v1/auth/send-code`、`/password/reset` | 仅内部成员支持的登录/重置场景；限流 | 原响应；未配置 SMTP 时不返回假成功或明文验证码 |
| POST `/api/v1/auth/register` | repair 模式关闭 | 403 REGISTRATION_DISABLED，不能通过直接 HTTP 开放注册 |
| GET `/session` | ACTIVE 成员 | id、displayName、role、memberVersion、allowedStores、repairProfileComplete、permissions；不含订单/其他成员隐私 |
| PATCH `/me/profile` | 当前成员；expectedVersion（成员）、displayName、contactPhone，可选 avatarAttachmentId | 更新工单资料；不接收 role、storeIds 或 birthday 作为提权/放行依据 |
| GET `/members` | 总部；role/status/keyword 分页 | 可供选工程师/管理成员的 DTO，不返回密码、token 或邀请原文 |
| POST `/invitations` | 总部；email、role、storeIds | 邀请 ID、一次性链接及 expiresAt，默认 72 小时；不自动发送邮件 |
| POST `/invitations/preview` | 有效 token 放请求 body，不放 URL query | email、role、expiresAt、existingAccount；供激活页确认目标账号 |
| POST `/invitations/accept` | 受有效邀请控制；token、displayName、新账号 password | 创建/绑定成员；已有账号必须先登录并匹配邀请邮箱，不能凭邀请重置现有账号密码 |
| PUT `/members/{id}/access` | 总部；expectedVersion、role、storeIds、reason | 修改身份/门店范围；锁定总部治理行，保护最后一个 ACTIVE 总部管理员 |
| POST `/members/{id}/disable`、`/enable` | 总部；expectedVersion、reason | 禁用/启用成员，撤销刷新令牌并审计 |

邀请 token 的有效性检查、账号创建/绑定、成员授权、邀请消费同一事务；两个请求不能使用同一个邀请创建两个账号。邀请码不是公共注册入口。首次总部管理员仅通过本地初始化命令建立。

第一阶段实现补充：已有非成员账号通过 `/auth/login` 携 `X-Repair-Invitation`，验证与邀请邮箱匹配的原密码。此时令牌的 `repair_auth_version=-1`，仅用于接受邀请，无法访问任何 repair 业务；激活后必须重新登录。正常令牌携成员 `auth_version`，权限/启用状态变更在数据库事务中递增该值，旧 access 和 refresh 均立即失效。登录返回最小 `user{id,email,displayName}` 及原 `token` 结构，不沿用校园资料完整性字段。

`repairProfileComplete = displayName 非空且账号 email 非空`。电话、头像按需要填写，生日不作为内部工单的必填项。前端先恢复认证再读取 repair session，缺资料弹出新的工单资料表单，权限/资料加载失败保持重试页，不挂载工作台。

## 3. 门店、分类和设施

| 方法与路径 | 角色与规则 |
| --- | --- |
| GET `/stores` | 总部全部；门店管理员仅负责门店；工程师不获得全门店目录 |
| POST `/stores`、PUT `/stores/{id}` | 总部；code/name/region/address、expectedVersion（更新）；停用用 status，不物理删除 |
| GET `/categories` | ACTIVE 成员读取启用分类；总部可查询停用项 |
| POST `/categories`、PUT `/categories/{id}` | 总部；code/name/status、expectedVersion（更新） |
| GET `/assets`、GET `/assets/{id}` | 总部或有对应门店授权的门店管理员；按 storeId/condition/lifecycle/keyword 筛选 |
| POST `/assets`、PUT `/assets/{id}` | 总部；storeId、assetCode、name、zone、brand/model/serialNumber；更新版本必填；不得迁移有活动工单的资产 |
| POST `/assets/{id}/condition` | 总部；expectedVersion、condition、reason；无活动工单时人工核正，不能强行覆盖维修中的状态 |
| POST `/assets/{id}/retire` | 总部；expectedVersion、reason；无活动工单才允许手动退役 |

资产不存在时，门店仍可在报修表填写设施名称和区域。后续总部在审核前关联台账；后端验证门店归属，不能由客户端自由组合 assetId 和 storeId。

## 4. 报修与维修命令

所有工单命令响应都包含新的 version，页面操作后用响应更新版本并重新拉详情。按钮根据后端返回的 allowedActions 展示，但后端仍独立检查权限、版本和状态。

| 方法与路径 | 请求字段 | 前置/结果 |
| --- | --- | --- |
| POST `/tickets` | storeId、assetId?、categoryId、title、description、facilityName、zone、brand?、model?、contactName、contactPhone、priority、attachmentIds | 门店管理员；创建 SUBMITTED。有 assetId 时设施身份/门店由后端加载，其余手填字段不得覆盖台账 |
| GET `/tickets` | storeId?、status?、priority?、assigneeId?、keyword?、from?、to?、page、size | 范围内列表；from/to 过滤 submittedAt；工程师 assignee 强制为本人 |
| GET `/tickets/{id}` | 无 | TicketDetail，采购按角色投影，包含 allowedActions、materialsReady、costStatus |
| GET `/tickets/{id}/events` | beforeId?、size<=100 | 逆序游标事件 `{items,nextCursor}`；按 visibility 过滤，包含独立的授权图片引用 |
| POST `/tickets/{id}/asset-link` | expectedVersion、assetId、reason | 总部，SUBMITTED；新资产必须同店且无其他活动工单，保存关联事件 |
| POST `/tickets/{id}/approve` | expectedVersion、procurementRequired、resolutionPlan、priority、note? | 总部，SUBMITTED → WAITING_PROCUREMENT 或 READY_FOR_DISPATCH |
| POST `/tickets/{id}/reject` | expectedVersion、reason | 总部，SUBMITTED → REJECTED |
| POST `/tickets/{id}/cancel` | expectedVersion、reason | 门店 SUBMITTED，或总部 WAITING_PROCUREMENT 且尚未下单 → CANCELLED |
| POST `/tickets/{id}/assign` | expectedVersion、engineerId、note? | 总部，READY_FOR_DISPATCH → ASSIGNED |
| POST `/tickets/{id}/withdraw-assignment` | expectedVersion、reason | 总部，ASSIGNED/ACCEPTED → READY_FOR_DISPATCH |
| POST `/tickets/{id}/reassign` | expectedVersion、engineerId、reason | 总部，IN_PROGRESS → ASSIGNED，原子改派并保留历史 |
| POST `/tickets/{id}/accept` | expectedVersion | 当前工程师，ASSIGNED → ACCEPTED |
| POST `/tickets/{id}/appointment` | expectedVersion、scheduledAt、note? | 当前工程师，ACCEPTED/IN_PROGRESS；记录上门时间事件 |
| POST `/tickets/{id}/start` | expectedVersion | 当前工程师，ACCEPTED 且材料就绪 → IN_PROGRESS |
| POST `/tickets/{id}/work-records` | expectedVersion、note、attachmentIds? | 当前工程师，IN_PROGRESS；追加过程记录，不覆盖原记录 |
| POST `/tickets/{id}/submit-result` | expectedVersion、resultNote、attachmentIds、replacementSerialNumber?、laborSuggestedAmount?、otherSuggestedAmount? | 当前工程师，IN_PROGRESS → WAITING_ACCEPTANCE，至少一张结果图 |
| POST `/tickets/{id}/confirm` | expectedVersion、note?；总部代验收另填 reason | 门店/总部，WAITING_ACCEPTANCE → COMPLETED，同事务更新/替换资产 |
| POST `/tickets/{id}/return` | expectedVersion、reason | 门店/总部，WAITING_ACCEPTANCE → IN_PROGRESS |
| PUT `/tickets/{id}/costs` | expectedVersion、expectedCostVersion?、laborAmount、otherAmount、note | 总部，在 WAITING_ACCEPTANCE/COMPLETED；首次无 expectedCostVersion，更新必须带；不改变工单终态 |

示例：

```http
POST /api/v1/repair/tickets/120/approve
Authorization: Bearer <access-token>
Idempotency-Key: 8f70a612-c1e6-4777-993f-e0cdb664544a
Content-Type: application/json

{"expectedVersion":0,"procurementRequired":true,"resolutionPlan":"PART_REPAIR","priority":"NORMAL"}
```

```json
{"ticketId":"120","ticketNo":"WX120","status":"WAITING_PROCUREMENT","version":1}
```

## 5. 采购、OCR 与到货

采购完整读取/修改和 OCR 只限总部。门店/工程师通过 materials 接口看摘要，不复用总部 DTO。

| 方法与路径 | 请求/响应与约束 |
| --- | --- |
| POST `/tickets/{id}/purchase-orders` | 总部；expectedTicketVersion；创建 DRAFT，返回 id、version、ticketVersion；一期每单最多一个有效采购单 |
| GET `/purchase-orders/{id}` | 总部完整采购资料，含人工确认状态、来源截图 ID 和 OCR 任务引用 |
| PUT `/purchase-orders/{id}` | 总部；expectedVersion、expectedTicketVersion、supplier、orderNumber?、orderedAt、currency、items[]、adjustmentAmount、sourceOcrJobId?；仅 DRAFT，金额后端计算 |
| POST `/purchase-orders/{id}/place` | 总部；expectedVersion、expectedTicketVersion；冻结明细、标记 ORDERED，工单进入 READY_FOR_DISPATCH |
| POST `/purchase-orders/{id}/cancel` | 总部；expectedVersion、expectedTicketVersion、reason；符合取消规则，工单回 WAITING_PROCUREMENT |
| POST `/purchase-orders/{id}/ocr-jobs` | 总部；sourceAttachmentId；202 `{jobId,status}`，DRAFT 才可启动，创建时不修改采购正文 |
| GET `/ocr-jobs/{id}` | 总部；任务状态、候选、warnings 或可读错误码。不会返回内部存储路径/凭据 |
| POST `/ocr-jobs/{id}/retry` | 总部；expectedVersion（任务）；FAILED 才可，受相同配额限制，202；采购仍需为 DRAFT |
| GET `/tickets/{id}/materials` | 工单可读者；商品名称/品牌型号/数量/到货数量/到货状态，不含单价总价、供应商或订单图 |
| POST `/purchase-orders/{id}/receipts` | 总部或该工单范围内的门店管理员/当前工程师；expectedVersion、expectedTicketVersion、items[{purchaseItemId,quantity}]、note?、attachmentIds?；数量指本次新增到货量 |
| GET `/purchase-orders/{id}/receipts` | 具备对应工单收货权限者；收货数量/时间/操作人摘要，采购价格不返回 |

采购草稿示例（品牌/型号须人工确认）：

```json
{
  "expectedVersion":0,
  "expectedTicketVersion":2,
  "supplier":"演示供应商",
  "orderNumber":"DEMO-001",
  "orderedAt":"2026-10-26T02:00:00Z",
  "currency":"CNY",
  "items":[{"purpose":"PART","name":"冷柜温控器","brand":"未标注","model":"DEMO-T01","quantity":1,"unitPrice":"168.00","specConfirmed":true}],
  "adjustmentAmount":"8.00",
  "sourceOcrJobId":null
}
```

明细的 specConfirmed 是本次人工确认事实，服务端存储审计确认人/时间；不能由 OCR 自动设置为 true。更新草稿时明细 ID 必须属于该单；删除明细只允许尚未下单的草稿。REPLACEMENT 计划须恰有一个 quantity=1 的 REPLACEMENT 项。

## 6. 文件接口

| 方法与路径 | 行为 |
| --- | --- |
| POST `/attachments` | multipart `file` + `purpose` + 作用域：故障图使用 storeId，业务过程图使用 ticketId；订单图另指定 purchaseOrderId；AVATAR 只绑定当前用户。返回 STAGED 附件 id/size/contentType/expiresAt；要求 Idempotency-Key |
| POST `/attachments/{id}/bind-order` | 总部；expectedVersion（采购）、expectedTicketVersion；绑定订单截图到 DRAFT，记录采购版本和总部事件；故障/结果图由相应业务命令自动绑定 |
| GET `/attachments/{id}/content` | 验证当前权限后流式返回本地私有文件；本地后端不开放静态目录。前端带 Bearer fetch Blob 预览 |
| GET `/attachments/{id}/download-url` | OSS 后端验证权限后返回 `{url,expiresIn:60}`；本地后端返回受保护 content 路径及 needAuthorization=true；不能在本地 URL query 里放 JWT |

purpose=FAULT/ORDER_SCREENSHOT/WORK_RECORD/RESULT/RECEIPT/AVATAR。FAULT 在提交时最多 6 张；单次过程记录最多 6 张；RESULT 1..6 张；采购订单图最多 6 张；RECEIPT 最多 6 张，按 HQ_ONLY 存储，避免收货单图片中的金额被其他角色读取。AVATAR 仅可绑定自己的资料。

上传权限：FAULT 为对应门店的门店管理员；ORDER_SCREENSHOT 为总部且采购仍为 DRAFT；WORK_RECORD/RESULT 为当前工单的受派工程师；RECEIPT 为该单有收货权限者；AVATAR 为任意 ACTIVE 成员本人。附件的门店、工单与采购单关系全部由后端校验，不能相信组合出来的客户端 ID。张数限制在绑定事务中锁定聚合后检查，避免并发超过上限。

暂存预览仅上传者可读；绑定后按照工单当前权限和用途读取。ORDER_SCREENSHOT、RECEIPT 绑定后只对总部读取。工程师的结果图不通过旧匿名资源 URL 暴露。

## 7. 通知、统计与导出

| 方法与路径 | 行为 |
| --- | --- |
| GET `/notifications` | 当前用户通知分页，附 unread；不含其他用户消息 |
| POST `/notifications/read` | body `{ids:["1","2"]}`，最多 100 个，限本人；幂等 |
| GET `/dashboard` | 按角色返回总部总览、负责门店总览或工程师本人待办，不返回无权统计维度 |
| GET `/reports/overview` | 总部；from/to/storeId?，返回新报修数、完成数、完成率、耗时及明确标记的当前未结数 |
| GET `/reports/stores` | 总部；按门店汇总，含故障原品牌型号和费用；费用区分采购记录支出与已确认完成单费用 |
| GET `/reports/engineers` | 总部；当前处理/完成任务、返修和耗时；跨改派贡献的细分报表一期不提供 |
| GET `/reports/export` | 总部；同报表筛选，CSV，最多 10,000 行，超过返回 EXPORT_LIMIT_EXCEEDED |

当前工程师最终完成的任务计入该工程师完成数；改派前历史工作记录可追溯，但不在一期伪造按贡献比例的绩效数据。导出文本对以 `= + - @` 开头的用户输入做公式注入处理，CSV 正确转义并使用 UTF-8 BOM。

## 8. 业务错误码

| HTTP | code | 用户操作 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR / BAD_REQUEST | 修正字段格式；不要无限自动重试 |
| 401 | UNAUTHORIZED | 按认证流程刷新一次，失败回登录 |
| 403 | MEMBER_DISABLED / FORBIDDEN / REGISTRATION_DISABLED | 停止操作，提示联系总部 |
| 404 | NOT_FOUND | 工单/门店/附件不存在或不可见 |
| 409 | VERSION_CONFLICT | 保留本地输入，重新拉取后人工确认再提交 |
| 409 | INVALID_TRANSITION / MATERIALS_NOT_READY | 显示当前状态/材料摘要，不能跳过状态直接改字段 |
| 409 | IDEMPOTENCY_CONFLICT / ASSET_HAS_ACTIVE_TICKET / ACTIVE_PURCHASE_EXISTS | 检查重复请求或打开已有记录 |
| 409 | EXISTING_ACCOUNT_LOGIN_REQUIRED | 先登录已有账号，再接受匹配邮箱的邀请 |
| 413 | FILE_TOO_LARGE | 更换小于 5 MiB 的图片 |
| 422 | PURCHASE_FIELDS_INCOMPLETE / EXPORT_LIMIT_EXCEEDED | 补齐人工确认字段或缩小导出范围 |
| 429 | TOO_MANY_REQUESTS | 使用 Retry-After 提示等待 |
| 503 | OCR_NOT_CONFIGURED / OCR_UNAVAILABLE / STORAGE_UNAVAILABLE | 保留表单；OCR 可切换手填，上传失败可重试 |

错误码需在 GlobalExceptionHandler 和安全异常入口中统一映射。OCR 异步失败通过 job.status/errorCode 返回 200 的任务查询 DTO；503 用于不能接受任务等同步错误，不混淆任务失败与查询失败。

## 9. 页面与端到端流程

| 路由（前端） | 页面/角色 |
| --- | --- |
| `/login`、`/activate#token=…`、`/password/reset` | 内部登录、受邀激活、找回密码 |
| `/` | 按 repair session 角色进入工作台 |
| `/tickets`、`/tickets/new`、`/tickets/:id` | 门店报修及角色范围内工单；详情展示允许动作和业务时间线 |
| `/tickets?status=SUBMITTED`、`/tickets?status=READY_FOR_DISPATCH` | 工单队列中的总部审核、待派单与工程师选择 |
| `/hq/purchases/:id` | 采购草稿/截图与 OCR 对照/下单和到货 |
| `/engineer/tasks` | 待接单、已接单、维修中、待验收；手机端优先 |
| `/stores`、`/assets` | 总部管理、门店范围内只读设施查询 |
| `/hq/members`、`/hq/reports` | 成员授权、总部报表 |
| `/notifications`、`/profile` | 本人通知抽屉和工作资料窗口 |

核心验收路径：门店登录提交冷柜故障 → 总部审核需配件 → 上传合成订单图并核对 OCR → 确认下单 → 派给工程师 → 工程师接单 → 门店登记到货 → 工程师开工及提交结果 → 门店验收 → 总部确认费用并查看汇总。

前端必须有加载、空数据、无权限、失败重试、版本冲突五种反馈。保存失败不清空输入；按钮提交中禁用；重试沿用同一 Idempotency-Key，用户实质修改内容后生成新 key。OCR 结果只由用户点击应用，不能在轮询完成回调里自动覆盖正在编辑的表单。

## 总部工程师派单一览

新增 GET `/api/v1/repair/dashboard/engineer-dispatch` 与 GET `/api/v1/repair/dashboard/engineer-dispatch/{engineerId}/tickets`，均由后端校验 HQ_ADMIN。前者分页查询工程师当前四阶段计数，后者分页查询该工程师当前未结工单，可用 status 筛选四阶段之一。详情见 [派单展示说明](14-engineer-dispatch.md)。原工单派单、改派、撤回与验收接口不变。

## 2026-10-26 邮件发送范围调整（覆盖此前业务邮件约定）

- 报修、审核、采购、派单、接单、维修、验收、SLA 提醒全部为站内通知，不发送邮件，不创建邮件投递任务。未读、已读、参与人权限与改派后的通用移除提醒保持原规则。
- SMTP 发送器仅允许 REGISTER、RESET_PASSWORD 验证码。当前仍为邀请制，公开注册及 REGISTER 验证码入口继续返回 403 REGISTRATION_DISABLED；公开 send-code 仅允许 RESET_PASSWORD，LOGIN 返回 403 VERIFICATION_SCENE_DISABLED。未新增公开注册，也未改变密码登录。
- 移除 GET `/delivery-jobs/failed` 和 POST `/delivery-jobs/{id}/retry`，不再提供业务邮件重试。
- V008 把历史 QUEUED/RUNNING/FAILED 业务邮件记录改为 CANCELLED，原因为 NOTIFICATION_IN_APP_ONLY；保留历史记录及已成功投递状态，不删除站内通知。
- 已移除 REPAIR_MAIL_ENABLED 开关和业务 SMTP 发送器/轮询器。SMTP 连接配置继续供验证码使用。
