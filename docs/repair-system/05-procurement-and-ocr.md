# 维修采购、订单 OCR 与材料到货

## 1. 已确认的采购触发点

门店设施故障 → 总部审核 → 总部在外部渠道购买配件/替换设备 → 将订单录入本系统 → 安排工程师。不是日常首次采购设备建档，也不是 Agent 自动选择商品或自动下单。

无需采购的工单由总部注明 REPAIR 方案后直接派单。需要采购时选择 PART_REPAIR 或 REPLACEMENT，工单进入 WAITING_PROCUREMENT。

## 2. 总部操作页面

页面上方固定展示原故障设施的名称、原品牌型号、门店与故障描述。下方为“本次采购”表单，不和原设施编辑表单共用字段对象。

```text
原故障设施：A店后厨冷柜，品牌X / 型号A（只读故障快照）

本次采购：
  订单截图 → 识别中/失败/识别完成
  左侧截图，右侧待确认候选
  订单号、供应商、下单时间
  明细：物品名称 / 配件或替换设备 / 品牌 / 型号 / 数量 / 单价
  运费、折扣等调整额 → 系统计算确认总额

  保存草稿 → 人工核对 → 确认“已下单” → 总部派单
```

支持先手填再上传截图，也支持 OCR 失败后继续手填。截图不含品牌/型号时留空并提示人工补充；确认所需品牌/型号可以填写“未标注”，但必须显式确认，不由识别程序编造。

## 3. 图片与任务流程

1. 总部先创建采购草稿，关联当前 WAITING_PROCUREMENT 工单。
2. 上传 JPG/PNG 到该采购草稿的 ORDER_SCREENSHOT 暂存附件，服务端验证格式、大小、尺寸并重新编码。
3. 总部通过 bind-order 将自己上传、未过期的暂存图绑定到采购草稿，再用 `POST /purchase-orders/{id}/ocr-jobs` 指定附件 ID，返回 202 和 jobId。OCR 来源必须已绑定该采购单且用途为 ORDER_SCREENSHOT；有权限的总部成员可以识别已绑定截图，不要求必须是原上传者。创建任务时固定图像哈希和来源引用。
4. 后端持久化任务，worker 在事务外调用 OCR。浏览器通过 jobId 轮询，终态后停止。
5. 任务成功返回全文片段、候选字段、原文依据/坐标及可用置信信息。结果不直接覆盖采购草稿或设施台账。
6. 总部选择候选并编辑；保存时带 expectedVersion 与 sourceOcrJobId，服务端校验来源归属。其他人已修改表单时返回版本冲突，由人决定重新应用，不自动覆盖。
7. 总部确认必需字段后执行“标记已下单”，冻结采购明细并将工单转 READY_FOR_DISPATCH。

上传新截图不覆盖旧对象。旧任务完成后仍可查看来源，但只对其关联草稿提供候选；采购已下单时禁止再启动识别或应用结果修改明细。

## 4. 识别与字段抽取边界

实现 `OrderOcrClient`，输入已验证的私有图片内容，输出文本行、坐标与 OCR 服务提供的置信信息；再由确定性的 `OrderFieldExtractor` 提取候选。不使用大模型补全未知事实。

可候选字段：orderNumber、supplier、orderedAt、items[]、adjustmentAmount、sourceTotal。采购明细候选为 name、brand、model、quantity、unitPrice；purpose 必须由总部选择，不能仅凭商品标题自动判定配件或替换设备。

| 字段情况 | 行为 |
| --- | --- |
| 图片没出现型号 | 返回 null 和 MISSING 提示，保留手填入口 |
| 一张图有多个商品 | 返回多个候选项，逐项选择；不把整张图强行映射为一个设备 |
| 商品数量/金额歧义 | 返回候选和原文，要求人工核对，不默认为 1 或 0 |
| OCR 服务没有置信度 | confidence 返回 null；字段规则匹配分数另存，不能伪装成 OCR 置信度 |
| 总金额与明细不相符 | 展示差额，人工确认折扣/运费；服务端以确认明细和 adjustmentAmount 计算总额 |
| 相同截图重复点击 | 同幂等 key 返回原任务；不同 key 可重试，但成功结果不会自动重复入单 |
| 图片只有部分订单信息 | 已识别项可用，其余继续手填；任务成功不等于表单完整 |

候选示例：

```json
{
  "jobId": "730f0683-63e8-4cec-aed7-c92da22f4ddd",
  "status": "SUCCEEDED",
  "candidates": {
    "orderNumber": {"value": "DEMO-20261026-001", "evidence": "订单编号 DEMO-20261026-001", "confidence": null},
    "items": [{"name": "冷柜温控器", "brand": null, "model": "DEMO-T01", "quantity": 1, "unitPrice": "168.00", "evidence": "冷柜温控器 DEMO-T01 数量1 单价168.00"}]
  },
  "warnings": ["BRAND_MISSING", "HUMAN_CONFIRMATION_REQUIRED"]
}
```

示例完全为合成数据。confidence 的单位/范围须在具体适配器中归一化并记录原始供应商单位；未知时为 null。提取结果仅是建议，总部确认后才成为采购事实。

## 5. OCR 服务与失败处理

一期保留可替换适配器：本地测试使用固定合成图片和 FakeOcrClient；实际适配器在第 4 阶段对照所选服务商官方 SDK/接口落地，可接通用印刷文字 OCR，再用规则抽取。当前仓库没有可复用的 OCR SDK 或已确认的 OCR 凭据，不能把现有 Spring AI 接口当成 OCR 已可用。

正式选型依据：中文订单截图识别、返回坐标、数据处理地域、超时、调用限额、预算。默认不外发图片；实际服务必须显式配置启用。未配置时任务返回 OCR_NOT_CONFIGURED，人工录入继续可用。记录厂商 requestId 和错误码，不记录密钥、完整订单原图或未脱敏识别全文到普通日志。

每图最多 5 MiB，默认每个总部账号每分钟 5 次、每天 100 次可配置。worker 执行前复核发起成员仍有效且具总部角色、采购仍为 DRAFT；不满足则取消任务。过期任务和 worker 租约恢复见[事务设计](03-data-and-transactions.md)。OCR 服务异常不能阻断无 OCR 的采购录入。自动重试只处理有明确重试意义的临时错误，不无限调用付费服务。

## 6. 采购确认与到货

每条明细 name、purpose、quantity、unitPrice 必填；brand/model 必须人工确认已有值或显式“未标注”。orderNumber 可选；supplier、orderedAt、币种 CNY 必填。数量为正整数，金额使用十进制定点数，前后端不以浮点数计算账目。

总额 `confirmedTotal = Σ(quantity × unitPrice) + adjustmentAmount`，结果必须 >=0。调整额允许折扣为负数，但不允许让订单总额为负。截图 sourceTotal 只用于核对，不直接覆盖计算结果。

下单后可先派单，订单状态与工单状态独立。门店收货人、当前受派工程师或总部可登记本次到货数量；服务器保存追加收货流水并条件累加 receivedQuantity，避免两人同时收货把数量加超。门店/工程师只看到商品、型号、数量和进度，不获得采购金额和原截图。

采购进度：

```text
DRAFT --总部确认--> ORDERED
ORDERED --部分到货--> PARTIALLY_RECEIVED
ORDERED/PARTIALLY_RECEIVED --全部收齐--> RECEIVED
DRAFT/ORDERED --尚无到货且未派单--> CANCELLED
```

全部到货后允许从 ACCEPTED 正式开工。无需采购的工单不要求采购记录。发生缺货、错货、退货等情况时，一期保留进度说明但不擅自模拟退款或库存冲销；有到货后记录更正/退货属于后续扩展，不用直接修改累计值解决。

## 7. 替换资产与费用归集

REPLACEMENT 工单的采购明细恰有一条 purpose=REPLACEMENT、quantity=1 的设备项。验收时新建设施台账，原故障资产保留并退役；配件明细不生成新的独立门店资产。无原资产 ID 的工单可在验收时建立替换后的首个正式资产，并保留原设施文字快照。

工程师结果可提交 laborSuggestedAmount/otherSuggestedAmount；总部通过费用确认接口决定最终金额。门店验收和总部费用确认是两个独立事实：工单可以已完成但 costStatus=PENDING，不因此阻止设备恢复使用。采购支出统计和完成工单总费用统计使用不同时间口径，见[领域设计](02-domain-and-workflows.md)。
