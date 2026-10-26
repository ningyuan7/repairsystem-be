# 角色落地、采购派单与命名调整

2026-10-26 完成。Java 自有命名空间统一为 `com.gaofeng`；源码、测试、Maven groupId、MyBatis 类型/namespace、Spring 扫描及脚本/文档引用同步。旧编译包清理，旧测试报告归档在 target/reports-before-gaofeng；运行 jar 无旧包名。后续命名约定保存在本地开发备注，不随源码上传。Git 忽略规则仅排除 backend/config 本机配置，Java 的 auth/config 源码可以正常纳入版本管理。

总部工单表提供“下一步”，详情展示审核、订购信息、派单、维修、验收的阶段及主要操作。审核不默认“无需采购”，必须明确选择直接维修、配件维修或替换设备。需要采购时进入原有采购工作区填写供应商、下单时间、物品/品牌/型号/数量/单价及订单截图；确认已下单后，采购页顶部可直接安排工程师。没有有效工程师时给出邀请/启用提示。原状态机不变：确认下单才能派单，材料收齐后才能开工；不替用户审核或推进现有工单。

退出登录清理旧页面地址；门店与工程师误入总部专属地址时，在受限组件请求前重定向到自己的工作台。后端权限保持原校验，允许的工单详情深链接保留。桌面和 390px 手机渲染已检查；截图位于 target/role-workflow-preview、target/repair-procurement-browser、target/repair-workflow-browser。

SMTP 只允许注册验证、找回密码场景；当前仍保持邀请制，不开放公开注册，因此公开发码入口仅支持 RESET_PASSWORD，LOGIN 返回 VERIFICATION_SCENE_DISABLED。工单、采购、派单、维修和 SLA 仅持久化站内通知，保留原可见范围、未读/已读和改派通用提醒。业务邮件发送器/任务轮询器/重试接口删除，V008 将旧 QUEUED/RUNNING/FAILED 投递取消并保留历史。诊断脚本只认证 SMTP，不发送自测邮件。

前端 TypeScript/Vite 构建通过；完整 95 项回归全部通过（失败 0、错误 0、跳过 0），包括真实 MySQL/Redis、Canal、浏览器流程、邮箱白名单与历史队列升级、实际 jar 启动。完成时间 2026-10-26 20:52:21（Asia/Shanghai），日志 target/workflow-mail-gaofeng-regression.log，汇总 target/workflow-mail-gaofeng-summary.json。OpenAPI 离线生成并验证全部引用，65 条路径、89 个 schema。
