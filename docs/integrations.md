> 当前邮件规则（2026-10-26 更新）：SMTP 仅用于注册验证和找回密码；工单业务通知全部在系统内。当前邀请制不开放公开注册，实际公开发码入口仅支持 RESET_PASSWORD。下方早期业务邮件启用/重试记录属于历史状态，已由 V008 和本次策略覆盖。验证码发送不依赖已移除的 REPAIR_MAIL_ENABLED。

# OCR 与 SMTP 接入

业务规则不变：OCR 结果仍需人工核对应用，邮件仍由现有通知任务投递并有限重试。

## 本地配置

示例为 backend/integrations.example.json。本机私有配置放在 config/local-integrations.json，由本机 config/start-local.ps1 读取；整个 config 目录已忽略，不提交凭据。部署环境直接设置同名环境变量，Spring Boot 不会自动读取 JSON 示例或 .env 文件。

本地 OCR 是当前默认方案：REPAIR_OCR_PROVIDER=LOCAL、REPAIR_OCR_LOCAL_URL=http://127.0.0.1:8601/v1/recognize。模型服务位于 services/ocr，使用 RapidOCR 的本地检测、方向分类与识别模型，CPU 推理，不需要任何云密钥。先安装并启动服务，GET /health 在真实预热成功后才返回 UP，再启用 REPAIR_OCR_ENABLED 并重启后端。

Windows 建议使用独立 CPython 创建 .venv。此次使用 CPython 3.13；Conda 自带旧版 C++ DLL 可能导致 ONNX Runtime 初始化失败，不通过修改系统或其他项目环境解决。

腾讯云保留为可选适配：REPAIR_OCR_PROVIDER=TENCENT 时才需要 REPAIR_OCR_SECRET_ID、REPAIR_OCR_SECRET_KEY、REPAIR_OCR_REGION，以及已开通的 GeneralAccurateOCR 权限。只有选择云服务才需要密钥；本地模式不读取或发送云凭据。

QQ SMTP 配置：smtp.qq.com、465、REPAIR_MAIL_AUTH=true、REPAIR_MAIL_SSL=true、REPAIR_MAIL_STARTTLS=false；REPAIR_MAIL_USERNAME 为完整邮箱，REPAIR_MAIL_PASSWORD 为邮箱 SMTP 授权码。其他供应商使用 587/STARTTLS 时，SSL=false、STARTTLS=true、STARTTLS_REQUIRED=true。保持服务器证书身份校验，禁止信任任意证书。

REPAIR_MAIL_ENABLED 控制业务通知邮件；验证码仍走现有邮件可用性检查。未提供可用配置时保留原错误提示，不返回假成功。当前基础健康检查不将可选邮件服务列为必需依赖，不能据整体 UP 判断实际投递成功。

## 联调边界

SMTP 连接与认证检查不发送邮件；实际投递需明确收件地址并授权测试邮件。OCR 单次真实识别使用合成订单图片。本地模式只访问回环地址、不向外部上传；显式选择腾讯云时调用才会使用云账号额度。凭据、认证头、验证码不写入联调日志。

本地初始化总部账号 admin@repair.local 是开发地址，无法作为真实收件箱；不为了测试更改其登录邮箱，也不把所有成员邮件重定向到测试邮箱。业务通知按实际成员的邮箱投递。

## 启动与复核

在 backend 执行 `powershell -File scripts/start-local.ps1 -RestartBackend`：读取本项目的私有配置，按需启动模型、等待模型预热就绪、启动 Java 与前端。JDK 21 通过 JAVA_HOME 指定；Node 通过 PATH 或 REPAIR_NODE 指定。不会停止其他项目占用端口的进程。

在 services/ocr 执行 `python -m venv .venv`，再用该环境 `python -m pip install -r requirements.lock`；直接使用基础依赖可读 requirements.txt。Windows 请明确使用独立 CPython，而非带旧运行库的 Conda Python。其他平台按 requirements.txt 安装，不直接复用 Windows 锁文件中的专用依赖。

在 backend 设置 JAVA_HOME 后运行：

```powershell
# 验证实际 SMTP 连接/认证；识别合成订单，不发送邮件
powershell -File scripts/check-integrations.ps1 -CheckOcr
# 邮件联调仅验证连接与认证，不投递测试邮件
```

联调脚本加载实际 Spring 配置，禁用该进程的自动工作任务与初始化账号，不创建报修或采购业务数据。结果写入 target/integration-check.json；ACCEPTED_BY_SMTP_SERVER 仅表示服务器接受，收件箱到达仍以实际邮箱为准。

## 官方依据

- [腾讯云 GeneralAccurateOCR](https://cloud.tencent.com/document/api/866/34937)
- [QQ 邮箱授权码说明](https://help.mail.qq.com/detail/106/985)
- [Angus Mail SMTP 参数](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html)

- [RapidOCR 本地安装与模型](https://rapidai.github.io/RapidOCRDocs/main/install_usage/rapidocr/install/)
- [ONNX Runtime 安装依赖](https://onnxruntime.ai/docs/install/)

## 2026-10-26 本机真实联调记录

- SMTP：SSL 465 连接、认证成功；经用户明确授权向配置邮箱自身发送 **1 封**“门店设施报修 · SMTP 接入联调”，服务器已接受。本次后续检查只认证，不重复发送；收件箱实际到达仍以邮箱为准。
- 本地 OCR：CPython 3.13.3、RapidOCR 3.10.0、ONNX Runtime 1.23.2；检测/方向分类/识别模型均从安装包中的本地文件加载，启动时完成真实识别预热。合成订单真实识别出 7 行文字、1 个商品候选，包含型号。
- 在线状态：Java 8080 健康 UP，模型 8601 健康 UP；通过当前前端代理和总部登录请求确认 configured=true、provider=LOCAL_RAPIDOCR；业务邮件 enabled=true。
- 非法输入：模型服务对不支持的 Content-Type 返回 415，对损坏图片返回 400。Java 适配器另外验证仅回环地址、禁止重定向、响应上限、置信度和故障分类。
- 业务保留：工单状态、成员/门店权限、采购/收货、维修验收、原 OCR 提取与人工确认、邮件重试实现未改；五组原有业务集成测试源码和预期未改。核对记录 target/business-rule-preservation.json。

证据：target/smtp-live-check.json、target/integration-check.json、target/current-integrations-status.json、target/local-ocr-live-check.log。完整业务回归以 target/integrations-full-regression.log 的最终结果为准。

最终回归完成于 2026-10-26 16:03:25（Asia/Shanghai）：**74 项测试，失败 0、错误 0、跳过 0，BUILD SUCCESS**。其中原有 71 项保持原预期，新增 3 项本地适配器测试；包含真实 MySQL/Redis、五组浏览器流程和实际 jar 启动检查。

## Canal 工程师缓存

新增可选 Canal 1.1.8 TCP 连接，独立复制账号与 destination，不需要 Kafka。完整配置、读取一致性和真实联调见 [工程师读取缓存](repair-system/15-canal-engineer-cache.md)。当前源码默认关闭；本机联调通过后单独启用。不要把 Canal 端口可达等同于数据新鲜，应检查完整 binlog 心跳。
