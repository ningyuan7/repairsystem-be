# 独立项目说明

本项目已从校园项目中物理抽离。后端 Maven 坐标为 `com.gaofeng.repair:repair-system-backend`，只有一个应用入口 RepairApplication，没有 repair/legacy-campus profile、源码编译白名单或指向原项目的符号链接。原项目的业务页面、控制器、配置、账号数据、凭据、密钥和 Git 历史均未复制。

`src/main/java/com/gaofeng/auth`、`user` 以及少量 `common`、`storage` 类是本项目拥有的基础源码，后续可独立修改；它们不是 Maven 对另一个工程的依赖。数据库迁移全部属于本项目，必须使用独立的 `repair_*` 数据库。私有文件默认写入 data/repair-private。

## 配置和启动

当前命令以 [根 README](../README.md) 与 [最终交付](repair-system/13-final-delivery.md) 为准。后端命令在后端仓库根目录执行，前端命令在独立前端仓库根目录执行；target 均相对后端仓库。需要 JDK 21、Maven 3.9+、MySQL 8、Redis 与 Node/npm。没有捆绑原项目 Maven settings、密钥或秘密。

本地密钥执行 `java scripts/GenerateKeys.java` 生成，存在时拒绝覆盖。不要复制生产密钥到开发目录。

## 集成与浏览器测试

普通 `mvn verify` 不依赖 MySQL/Redis。完整集成测试按需开启，测试仅创建和删除随机 repair_test_<UUID> 数据库；Redis 使用第 15 库和随机短期键，不执行 FLUSHDB。集成测试自动生成隔离临时 JWT 密钥，退出时清理。

复制 repair-test.example.yml 为 config/repair-test.yml 并填写独立测试凭据，或配置以下环境变量：

```text
REPAIR_TEST_DB_URL=jdbc:mysql://localhost:3306/repair_test
REPAIR_TEST_DB_USERNAME=repair_test
REPAIR_TEST_DB_PASSWORD=自行设置
REPAIR_TEST_REDIS_HOST=localhost
REPAIR_TEST_REDIS_PORT=6379
REPAIR_TEST_REDIS_PASSWORD=
```

测试数据库账号需要创建和删除测试库的权限。不得使用生产连接。浏览器测试通过 REPAIR_FRONTEND_DIST 读取独立前端的构建产物：先在前端仓库执行 npm ci 与 npm run build，再将 REPAIR_FRONTEND_DIST 设置为其 dist 绝对路径。测试工具在后端仓库安装：

```powershell
$env:REPAIR_FRONTEND_DIST='C:/path/to/RepairSystem-frontend/dist'
npm install --prefix target/repair-browser-tools --no-save playwright@1.51.1
node target/repair-browser-tools/node_modules/playwright/cli.js install chromium
mvn -DskipTests package
mvn '-Drepair.integration=true' '-Drepair.browser=true' `
  '-Dprotection.integration=true' '-Drepair.packaged=true' verify
```

Node 不在 PATH 时配置 REPAIR_NODE；Playwright 位于其他目录时配置 REPAIR_PLAYWRIGHT_MODULE。以上都是测试工具位置，不是业务源码依赖。

## 抽离与验证记录

抽离前完整验收为 71 项通过。抽离后于 2026-10-26 14:51:07（Asia/Shanghai）再次完整验收：**71 项测试，失败 0、错误 0、跳过 0，BUILD SUCCESS**。新前端独立 npm ci 与生产构建通过；实际 jar 健康检查、会话与旧接口隔离通过；OpenAPI 在本项目重新离线生成。浏览器测试工具已安装到 target/repair-browser-tools，不需要原目录中的工具。

日志：target/standalone-full-verification.log；JUnit XML：target/surefire-reports；前端构建：frontend-build.log。原目录中的报修源码已迁出，校园前后端入口已恢复，迁出前的可恢复副本保存在原后端 target/repair-extraction-backup，独立项目运行不依赖该副本。恢复后的原校园后端打包、前端生产构建均通过；原项目未做运行环境回归。真实腾讯 OCR、SMTP、线上部署及性能压测未执行。
