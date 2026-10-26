> 历史阶段记录：独立项目的当前目录、配置与运行命令以 [独立项目说明](../standalone-project.md) 为准。

# 第一阶段交付与本地运行

日期：2026-10-26。范围对应原计划的「基础隔离与身份」，同时提前实现身份管理所必需的门店维护和数据库幂等。不以此记录声明整个工单系统已经完成。

## 1. 已实现范围

- 新入口 `com.gaofeng.repair.bootstrap.RepairApplication`，打包入口已切换。只扫描 repair 和显式导入的共享身份基础；不注册旧社区、聊天、AI、Kafka/ES 相关应用 Bean。
- 独立 `repair.yml`，不导入旧 `application-local.yml`。启动依赖 MySQL 8 与 Redis；无 Kafka、ES、Canal、AI/OCR 或 SMTP 配置也可运行基础功能。文件能力仍待第二阶段，当前没有假上传入口。
- Flyway V001：兼容复用 Mapper 的用户/登录日志、成员、门店、范围授权、邀请、总部治理锁、审计和幂等。迁移前要求数据库名为 `repair_*`，拒绝误用旧业务库。
- 内部密码登录、刷新/退出、邮箱验证码和密码重置后端合同；验证码仅在 SMTP 已配置时可用。开放注册直接拒绝。新前端提供密码登录；找回密码自助页后续补齐，当前明确联系总部。
- 三角色、每请求查询成员状态和门店范围；角色/停用变更原子递增 `auth_version`，旧 access/refresh 失效。Redis 刷新令牌清理放在数据库提交后，清理失败也不能复活旧会话。
- 总部创建 72 小时一次性邀请；新账号设置密码激活，已有账号先验证原密码再接受邀请。不能通过邀请重置已有账号密码。激活后重新登录。链接 token 在 URL fragment 中，不进入服务端访问日志；数据库不保存明文 token。
- 总部成员列表、角色/门店范围调整、停用/启用，保护最后一个有效总部管理员；门店目录和新增/编辑/启停；门店管理员仅见负责门店，工程师不获得门店目录。
- 所有已登录业务写请求使用 UUID 幂等键及事务内结果记录；相同内容回放原响应，不同内容 409；已有聚合校验版本。身份和门店变更有审计，失败整笔回滚。
- React 18 + TypeScript + Ant Design 5 页面：内部登录、激活、资料完善窗口、基础工作台、门店管理、成员授权。会话或资料加载失败时不加载业务内容；页面明确当前未开放工单功能。

## 2. 本地运行

使用 JDK 21、Maven、MySQL 8、Redis、Node.js。不更改旧数据库、配置、部署文件；不启动旧校园主类。下列 SQL 为手动准备独立开发库的示例，尚未替你创建正式开发账号。

```sql
CREATE DATABASE repair_system CHARACTER SET utf8mb4;
CREATE USER 'repair'@'localhost' IDENTIFIED BY '自行设置的开发密码';
GRANT ALL PRIVILEGES ON repair_system.* TO 'repair'@'localhost';
```

PowerShell，在后端目录设置当前终端环境变量，密码按本机实际填写。不要把值提交到 Git：

```powershell
$env:JAVA_HOME = '你的 JDK 21 目录'
$env:REPAIR_DB_USERNAME = 'repair'
$env:REPAIR_DB_PASSWORD = '独立开发库密码'
$env:REPAIR_REDIS_HOST = 'localhost'
$env:REPAIR_REDIS_PORT = '6379'
$env:REPAIR_REDIS_DATABASE = '8'
# Redis 有密码时另设 REPAIR_REDIS_PASSWORD。
# JWT 默认读取已有本地 config/keys/private.pem 与 public.pem，不打印或提交密钥。
mvn spring-boot:run
```

数据库 URL 可通过 `REPAIR_DB_URL` 设置；必须指向独立 `repair_*` 库，并保留 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`。服务默认端口 8080（`REPAIR_PORT` 可改），前端 Vite 默认代理到 8080。浏览器 Origin 默认允许 localhost/127.0.0.1 的 5173 端口；自定义前端端口需配置逗号分隔的 `REPAIR_CORS_ORIGINS`。

首次初始化总部管理员时，启动前额外设置：

```powershell
$env:REPAIR_BOOTSTRAP_ENABLED = 'true'
$env:REPAIR_BOOTSTRAP_EMAIL = '你的内部邮箱'
$env:REPAIR_BOOTSTRAP_PASSWORD = '自行设置的强密码'
$env:REPAIR_BOOTSTRAP_NAME = '总部管理员姓名'
mvn spring-boot:run
```

这是显式启用的本地初始化命令：只在没有有效总部成员时创建；绝不覆盖已有账号。密码至少 8 位、包含字母和数字、UTF-8 长度不超过 72 字节。没有内置默认账号/密码。首次成功后将 `REPAIR_BOOTSTRAP_ENABLED` 设回 `false`，移除终端中的初始化密码。普通成员由总部工作台邀请；本阶段不自动发送邀请邮件。

前端在同级 `frontend` 目录运行：

```powershell
npm ci
npm run dev
```

打开 http://localhost:5173。登录后，资料不完整先弹窗保存，再显示工作台。先维护门店，再邀请门店管理员或工程师。总部角色只能由已有总部管理员单独授权。

SMTP 非基础启动依赖。若要启用验证码，自行配置 `SPRING_MAIL_HOST/PORT/USERNAME/PASSWORD` 及供应商所需 TLS 属性；没有配置时返回明确的 503，不输出明文验证码。本次没有实际发邮件。

打包：`mvn -DskipTests package`，产物 `target/repair-system.jar`，默认运行 repair 配置；`java -jar target/repair-system.jar`。当前是本地开发阶段，未部署。

## 3. 验证方式与证据

真实数据库测试读取当前本地连接凭据，但只创建 `repair_test_<随机UUID>` 临时库，测试结束严格匹配名称后删除；不会迁移或删除原业务库。Redis 使用第 15 库，不执行 FLUSHDB。测试只使用合成账号与门店，不连接 SMTP/OCR/OSS。

```powershell
mvn '-Dtest=RepairIdentityIntegrationTest,RepairMigrationSafetyTest,AuthServiceTest' `
    '-Drepair.integration=true' '-Drepair.browser=true' test
```

浏览器测试需本机可用 Playwright 及匹配 Chromium。当前本机复用已有的 Playwright 1.51.1，路径通过 `REPAIR_PLAYWRIGHT_MODULE` 指向；测试脚本位于 `scripts/repair-browser-test.cjs`，针对真实临时后端，不拦截或伪造业务 API。`RepairIdentityIntegrationTest` 中本机 Node/Playwright 路径仅用于本次开发验收，后续 CI 需改为配置参数。

验证日志：`target/repair-test.log`；浏览器日志 `target/repair-browser.log`；截图与结果 `target/repair-browser/`；前端构建日志 `target/repair-ui-build.log`。这些都是可重新生成的本地产物，未作为源码提交。

覆盖：空库迁移/重复迁移、旧模块隔离、开放注册拒绝、三角色/门店范围、一次性邀请、已有账号证明、账号停用/重新启用、refresh 禁止访问业务、权限代次、普通/并发幂等、版本冲突、未知字段拒绝、资料完整性、CORS、故障回滚、并发保留总部管理员、错误 requestId、邮件缺配置，以及桌面/390px 手机浏览器操作。

2026-10-26 本机验收结果：`RepairIdentityIntegrationTest` 12 项、`RepairMigrationSafetyTest` 2 项、复用 `AuthServiceTest` 7 项，共 **21 项成功，0 失败/错误/跳过**。包含降权后旧幂等响应拒绝回放的新增用例。真实 Chromium 流程完成总部登录/资料完善/新增门店/邀请工程师/手机激活及登录/直接请求总部 API 被拒绝；1440px 桌面与 390px 手机截图已检查，页面错误为 0。手机侧栏收起后的残留文案已修正，最终视觉回归单独记录在 `target/repair-ui-final-test.log`。

`npm run build` 通过 TypeScript 与 Vite 生产构建；`mvn ... package` 通过，生成 `target/repair-system.jar`。已检查 Manifest 的 `Start-Class`、repair 配置、迁移 SQL 和隔离 Filter 注册资源。前端当前仍有组件包超过 500KB 的构建提示，尚未做按页面拆包；不作为已经完成性能优化的证据。

## 4. 下一阶段与限制

- 下一阶段按既定文档实现分类、设施台账、私有图片和无需采购的报修闭环，再进入采购/收货及 OCR；目前这些接口和统计都没有实现。
- 暂保留旧源码及编译依赖，保证分阶段迁移；它们不会进入新应用的组件扫描。依赖物理裁剪放在原计划第六阶段，不把“没有 Bean”表述为“Jar 已删除依赖”。
- 幂等响应使用 AES-GCM，密钥派生自当前私钥。私钥轮换与 24 小时回放的兼容需要维护安排，详见数据文档；生产前应完善独立响应密钥轮换。
- 仅对本阶段做验收，后续工单跨店访问、改派、材料就绪、替换资产、私有附件、OCR 租约、报表等尚不能宣称通过。
