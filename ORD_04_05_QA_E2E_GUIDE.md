# ORD-04/05 售后退货证据隔离验收指南

版本：`v2.4.1`

隔离地址：`http://127.0.0.1:3000`

安全对象：Tenant-A、店铺 `101/102/103`、订单号前缀 `QA-ORDER-`。生产端口 `2000`、真实闲鱼店铺、真实退款/退货/换货操作不在测试范围内。

## 1. 能力边界

- 系统不会向闲鱼提交退款同意、拒绝、退货、换货或补发。
- 本批 API 只保存测试夹具，或保存测试人员已在平台核对过的售后物流事实。
- 售后运单预检和记录响应必须显示 `platformWrite=NOT_PERFORMED`。
- QA 夹具接口仅在 Spring `qa` profile 且商品批 QA Mock 白名单完整时注册，且外部平台网络调用为 0。

## 2. 创建可回放夹具

选择 Tenant-A、店铺 101/102/103 中订单号以 `QA-ORDER-` 开头的订单记录 ID：

```bash
curl -X POST \
  -H "Authorization: Bearer $QA_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"requestId":"qa-ord-04-fixture-001"}' \
  http://127.0.0.1:3000/api/qa/order-after-sales/fixtures/ORDER_RECORD_ID
```

返回必须包含 `safeFixture=true`、`platformNetworkCalls=false`、`refundCaseId` 和完整订单详情。夹具会生成一个退货退款案例和一条买家退回的运输中运单；重复调用只更新同一夹具，不复制案例或运单。

## 3. 双向运单预检

调用 `POST /api/order-matrix/refunds/{refundCaseId}/return-shipment/preview`。请求字段：

- `direction`：`BUYER_TO_SELLER` 或 `SELLER_TO_BUYER`；
- `shipmentStatus`：`PENDING_PICKUP/IN_TRANSIT/DELIVERED/RECEIVED/EXCEPTION/RETURNED`；
- `logisticsCompanyName`、`trackingNumber` 必填；
- `receivedTime` 不能早于 `shippedTime`。

预检只返回精确确认文案，不写数据库。超过范围的方向/状态、含空格或不足 4 位的运单号、倒置时间应返回 400。

## 4. 记录已确认事实

将预检的 `confirmationText` 原样带入 `POST /api/order-matrix/refunds/{refundCaseId}/return-shipment/record`，并设置 `platformConfirmed=true`。

验收点：

1. 首次请求生成一条 `xianyu_return_shipment`、一条 `RETURN_SHIPMENT_RECORDED` 订单事件和一条统一操作审计；
2. 相同 `requestId` 重放返回同一运单且 `idempotentReplay=true`；
3. 相同售后、方向和运单号使用不同请求 ID 时返回 409；
4. 未勾选平台确认、确认文案过期或不匹配时不落库；
5. 非白名单店铺、跨租户退款 ID 和缺少 `action:order-write` 的角色不能记录；
6. 详情回读保留订单上下文，并展示售后类型、期限、平台下一步、运单方向、状态、来源和覆盖证据。

## 5. UI 与响应式

- 桌面 1920×1080、1366×768：抽屉与弹窗内部滚动，底部动作持续可见。
- 手机 390×844：退款卡片、期限和表单单列重排，无页面级横向滚动。
- 空状态：订单列表存在退款状态但没有案例时显示“尚未形成可核验案例/未同步”，不显示为退款 0。
- 加载、错误、无权限和长运单号必须有可读反馈；弹窗支持 Tab 循环、Esc 关闭与焦点恢复。
