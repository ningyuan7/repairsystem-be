# 前后端独立仓库

当前源代码拆为两个同级目录，可分别上传：

- RepairSystem-backend：根目录直接包含 pom.xml、src、scripts、docs、services/ocr；JDK 21。
- RepairSystem-frontend：根目录直接包含 package.json、src、public、vite.config.ts；npm ci / npm run build。

每个目录有自己的 .git、.gitignore 和 README，没有互相引用源码、node_modules 或私有配置。Git 尚未提交，也没有配置远程仓库或执行上传。

后端启动脚本只管理后端、OCR 和 Canal；前端启动脚本只管理 Vite。后端私有 config、data、OCR 虚拟环境和 Canal 本机运行目录整体迁移保留，不上传。数据库、账号、工单、业务状态及权限不因拆分而修改。

普通后端构建不需要前端目录。仅可选浏览器联调需要独立前端构建产物，通过 REPAIR_FRONTEND_DIST 指定任意位置的 dist。开发代理使用 VITE_DEV_PROXY_TARGET，生产接口前缀使用 VITE_API_BASE_URL；跨域地址需配置后端 REPAIR_CORS_ORIGINS。

业务阶段文档保留历史实施记录；其中 backend/ 前缀现对应后端仓库根目录，frontend/ 前缀对应独立前端仓库根目录。最新运行步骤以两个仓库 README 和 docs/standalone-project.md 为准。

原 RepairSystem 目录只保留迁移指引、旧 Git 元数据和本机历史日志，不再作为开发或启动入口。

## 拆分验收（2026-10-27）

- 独立后端 mvn verify 成功，28 项默认测试通过；前端 TypeScript / Vite 生产构建成功。
- 全量隔离回归 95 项通过，失败、错误和跳过均为 0，包含真实 MySQL / Redis / Canal、六组浏览器流程和可执行 jar 验证。
- 原有账号在新目录启动的服务上登录成功，总部和门店角色跳转正常；未推进现有工单状态。
- Java 后端 149 个 main 文件和前端 17 个 UI 源文件哈希与迁移前一致；前端 API 客户端仅增加可配置的接口前缀。
- OpenAPI 在后端新路径生成，65 条路径、89 个 schema；前端合同快照一致。
- 后端与 OCR 健康检查 UP，Canal cdcFresh=true；两个启动脚本可重复执行。
- Git 可上传范围已检查：私有 config、data、密钥、target、node_modules、dist、.local 和 OCR .venv 均被忽略，Java 源码中的 config 包可正常纳入版本控制。

日志位于后端 target/split-backend-verification.log、target/split-full-verification.log，前端 .local/split-frontend-build.log。汇总为后端 target/split-verification-summary.json；本地日志和报告不提交。

本地 AI 开发约定已移出源码目录，保存在 Git 元数据的本地备注中；AGENTS.md、AGENT.md 及助手配置目录均不纳入上传。
