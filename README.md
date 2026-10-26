# RepairSystem Backend · 门店设施报修后端

面向连锁门店内部：门店提交设施报修，总部审核并采购配件或替换设备，上传订单图并通过 OCR 辅助填写，派单给工程师维修，门店验收，总部汇总费用和时效。

这是独立 Java 后端仓库，前端单独维护于 RepairSystem-frontend。JDK 21 / Maven 3.9+ / Spring Boot 3.2.4 / MyBatis / MySQL 8 / Redis / Flyway / Spring Security JWT；可选 Canal 工程师列表预热和本地 OCR，不依赖前端或其他项目源码。

```text
src/             Java 业务、数据库迁移和测试
scripts/         启动、诊断、OpenAPI 和可选浏览器联调脚本
docs/            业务设计、接口合同、集成与验收文档
services/ocr/    可选本地 OCR 推理服务
.env.example     环境变量示例（Spring 不自动加载）
```

## 构建和运行

所有后端命令均在本仓库根目录执行。

1. 建立独立 repair_system 数据库，按 .env.example 设置进程环境变量和 Redis 连接。
2. 执行 `java scripts/GenerateKeys.java` 生成自己的 JWT 密钥。
3. 首次启动配置 REPAIR_BOOTSTRAP_ENABLED=true、REPAIR_BOOTSTRAP_EMAIL、REPAIR_BOOTSTRAP_PASSWORD、REPAIR_BOOTSTRAP_NAME；初始化后关闭 bootstrap。
4. 执行 `mvn verify`，随后 `java -jar target/repair-system.jar`。默认端口 8080，健康检查 GET /actuator/health。

本机私有配置继续位于被 Git 忽略的 config/。已配置的环境可以执行 `powershell -ExecutionPolicy Bypass -File scripts/start-local.ps1`，只启动后端及已启用的本项目 Canal/OCR；前端独立启动。OCR 安装和配置见 [集成说明](docs/integrations.md)。SMTP 仅用于注册验证和找回密码，业务通知全部在系统内。

## 前后端连接

开发前端将 /api 代理到后端；生产环境可使用同源反向代理。跨域部署时将前端域名加入 REPAIR_CORS_ORIGINS（逗号分隔）。前端 VITE_API_BASE_URL 应包含 /api/v1。

接口合同为 [OpenAPI](docs/repair-system/openapi.json)。打包后执行 `powershell -File scripts/generate-repair-openapi.ps1` 离线生成，接口变更时同步前端合同快照。

## 测试与上传

普通 `mvn verify` 不需要前端、数据库或 Redis。完整集成测试使用 REPAIR_TEST_* 或 config/repair-test.yml，只操作隔离测试库。浏览器联调是可选项：先独立构建前端，再设置 REPAIR_FRONTEND_DIST 为它的 dist 绝对路径；详见 [独立项目说明](docs/standalone-project.md)。

本目录是独立 Git 仓库，可单独关联后端远程仓库并上传。config/、data/、target/、密钥、环境文件和 OCR 虚拟环境均被忽略；只提交源码、依赖清单、示例配置与文档。

- [业务文档](docs/repair-system/README.md)
- [前后端仓库拆分说明](docs/repository-split.md)
- [Canal 缓存与预热](docs/repair-system/15-canal-engineer-cache.md)
