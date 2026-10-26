# 连锁门店设施报修系统：开发设计基线

日期：2026-10-26。状态：阶段 1–6 已实现并完成本地验收。包含内部身份、三角色、设施报修、采购收货、OCR 候选、通知、超时提醒和总部报表。当前运行、构建和验证结果以[独立项目说明](../standalone-project.md)和[最终交付记录](13-final-delivery.md)为准；早期交付记录保留为实施历史。

## 1. 已确认的目标

本仓库是供总部、门店管理员、工程师使用的独立设施报修系统，当前仓库根目录为后端，前端在独立 RepairSystem-frontend 仓库。当前工程边界与运行说明见 [独立项目说明](../standalone-project.md)。原开源 RepairSystem 仅作为业务流程参考。

用户已确认：**总部采购发生在报修之后，采购对象是维修配件或替换设备；上传订单截图，通过 OCR 辅助填写采购物品的品牌、型号等信息，再安排工程师维修，最终由总部汇总数据。**

主流程：

```mermaid
flowchart LR
    A[门店管理员提交报修] --> B[总部审核故障及处理方案]
    B --> C{是否需要采购}
    C -->|需要| D[总部采购并上传订单截图]
    D --> E[OCR候选字段 → 人工核对采购记录]
    E --> F[记录已下单]
    C -->|不需要| G[待派单]
    F --> G
    G --> H[总部派单 → 工程师接单约时间]
    H --> I[检查材料到货 → 开始维修]
    I --> J[工程师提交维修结果]
    J --> K[门店验收或退回继续维修]
    K --> L[总部汇总门店、设施、费用与时效]
```

采购在实际购物渠道完成。本系统记录采购订单、到货及维修关联，不代替电商平台下单，不接支付或退款接口。OCR 是识别与字段预填功能，不引入 Agent 自主决策或自动采购。

## 2. 阅读顺序

| 文档 | 回答的问题 |
| --- | --- |
| [现有能力与复用清单](01-reuse-inventory.md) | 哪些代码能保留，哪些必须改，哪些应从新应用隔离 |
| [业务流程、角色与状态机](02-domain-and-workflows.md) | 总部、门店、工程师分别做什么，异常怎样流转 |
| [数据模型与事务一致性](03-data-and-transactions.md) | 表结构、索引、数据权限、并发、幂等如何设计 |
| [接口与前端页面契约](04-api-and-ui.md) | 请求、响应、权限、错误码和页面如何衔接 |
| [采购与 OCR 设计](05-procurement-and-ocr.md) | 截图怎样识别、人工确认、到货及替换设备怎样关联 |
| [开发规范、阶段计划与验收](06-delivery-and-acceptance.md) | 按什么顺序开发，每个阶段如何证明完成 |
| [界面实施依据](07-ui-design.md) | 目标用户、参考来源、布局和设计变量 |
| [第一阶段交付与运行](08-phase1-delivery.md) | 当前实现、启动命令、验证证据与后续范围 |
| [报修主线交付](09-phase2-delivery.md) | 设施、工单、私有图片与维修验收 |
| [采购收货交付](10-phase3-delivery.md) | 采购、替换资产与费用 |
| [OCR 交付](11-phase4-delivery.md) | 任务恢复、候选确认与供应商边界 |
| [通知与报表交付](12-phase5-delivery.md) | 通知、超时、费用口径和导出 |
| [最终交付与本地运行](13-final-delivery.md) | 完整验收、构建隔离与启动配置 |
| [工程师派单一览](14-engineer-dispatch.md) | 总部实时派单负载、工单明细与权限 |
| [完整 OpenAPI](openapi.json) | 77 个接口操作的机器可读合同 |

## 3. 一期边界

一期包含：单个总部下的多门店；三类业务角色；管理员邀请加入；设施台账与手动填写的未知设施；报修、审核、采购、OCR、到货、派单、维修、验收；私有图片；站内通知；总部统计及 CSV 导出。

一期不包含：公开社区、聊天、开放注册、在线支付、自动购物、库存仓储、跨公司 SaaS、多租户计费、AI 诊断、Agent、采购退货结算、多个采购单同时服务同一工单。维修过程记录是工单业务记录，只对获授权参与者展示。

采购约束：每个工单同一时刻最多一个未取消的采购单；该采购单可以有多条配件/设备明细，允许分批到货。历史取消单保留。后续多个追加采购单须单独扩展合同、金额与材料就绪规则。

## 4. 本版采用的业务默认值

以下是为使设计可实施而选定的默认值，后续调整应同步修改接口、状态机与测试：

| 决策 | 默认行为 |
| --- | --- |
| 总部采购是否必需 | 总部审核时选择；无需采购可直接进入待派单 |
| 何时可派单 | 所需物品已记录“下单”即可，允许先接单和约时间 |
| 何时可正式开工 | 无需采购，或全部采购明细已到货；否则返回材料未就绪 |
| 验收人 | 本门店管理员；总部可以注明原因代验收，单独留痕 |
| 角色 | `HQ_ADMIN`、`STORE_MANAGER`、`ENGINEER`；一个用户一期只持有一种角色 |
| 门店范围 | 一个门店管理员可负责多家门店；总部管理全部门店；工程师只看当前派给自己的工单 |
| 首次登录资料 | 姓名/显示名、邮箱；电话按业务填写，头像与生日均不再作为工单系统的强制门槛 |
| 文件 | 一期支持 JPG/PNG，每张最多 5 MiB；故障、订单、完工图片分别授权 |
| 费用 | CNY；采购金额与人工/其他维修费用分别记录，总部确认后统计 |
| 旧校园数据 | 不自动迁移成门店、资产或业务身份；不清库、不覆盖历史数据 |

## 5. 技术与改造路径

后端延续 Java 21、现有 Spring Boot 3.2.4、MyBatis XML、MySQL 8、Redis、Spring Security/JWT。前端延续 React 18、TypeScript、Vite。这些是当前代码的改造起点，不表示它们是最新版本或已完成新系统的兼容与安全验收。

采用按业务分包的模块化单体，新增 `com.gaofeng.repair`。不在第一版引入微服务、流程引擎或 Kafka/Canal 依赖。工单查询先使用有索引的 MySQL；通知、OCR 使用数据库任务与受限线程池。当前采用私有本地文件存储，复用图像验证和清洗能力，不依赖 OSS。若后续启用对象存储，需要单独实现存储适配与短期签名授权。

已完成物理抽离：本项目独立编译、独立配置、独立数据库与文件目录，不含原项目业务页面或控制器。认证等基础能力是本项目自己拥有的源码。

当前已完成六个开发阶段及独立工程抽离，不迁移其他项目业务库，不发送真实邮件、不调用收费 OCR，也没有部署。

## 6. RepairSystem 参考基线

- 仓库：[CheungChingYin/RepairSystem](https://github.com/CheungChingYin/RepairSystem)。
- 本次实际读取的提交：[`e9258a7206612ed0cc56b8f7a09ed43d50cf4153`](https://github.com/CheungChingYin/RepairSystem/tree/e9258a7206612ed0cc56b8f7a09ed43d50cf4153)，提交日期 2023-02-22。
- 已阅读源文件：`OrderController`、`OrdersServiceImpl`、`CompleteOrderServiceImpl`、`ClassServiceImpl`、`QRCodeController`、`SchedulerTask`、`ShiroConfig`、实体、SQL、POM、LICENSE。源码仅下载到被 Git 忽略的 `target/repair-reference/`，未运行。
- 该版本采用 Spring Boot 2.0.6、Java 8；参考其报修受理、历史、地点与通知思路，不迁入旧框架和安全配置。
- 原项目为 MIT License。后续若复制实质代码，须在实际分发材料中保留原版权和许可；本轮未复制其业务实现。

参考对应关系：

| 参考点 | 新系统处理 |
| --- | --- |
| 地点、设备定位 | 改为门店、店内区域、设施台账 |
| 报修、接手、完工 | 扩展为总部审核、维修采购、派单、执行、门店验收 |
| 完工历史 | 同一工单保留全部阶段，以状态查询历史 |
| 图片、二维码、通知 | 图片私有授权；二维码后续扩展；站内通知持久化 |
| 机房设备可用量变化 | 根据设施状态与活动工单查询，避免随提交次数加减 |

源码入口：[工单 Controller](https://github.com/CheungChingYin/RepairSystem/blob/e9258a7206612ed0cc56b8f7a09ed43d50cf4153/src/main/java/com/repairsystem/web/controller/OrderController.java)、[设备计数服务](https://github.com/CheungChingYin/RepairSystem/blob/e9258a7206612ed0cc56b8f7a09ed43d50cf4153/src/main/java/com/repairsystem/service/Impl/ClassServiceImpl.java)、[许可证](https://github.com/CheungChingYin/RepairSystem/blob/e9258a7206612ed0cc56b8f7a09ed43d50cf4153/LICENSE)。本系统新增的采购、OCR、到货、总部统计规则均为本次设计，不声称为原项目已实现能力。

## 工程师读取性能

[Canal 缓存与预热](15-canal-engineer-cache.md)记录当前实现、一致性边界、运维与验证；此前阶段文档中的“不接入 Canal”属于当时范围，后续扩展以本节为准。

[角色落地、采购派单、邮件范围与 gaofeng 命名调整](16-workflow-mail-and-namespace.md)
