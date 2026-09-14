# PUB-01～03 隔离 QA E2E 指南

## 运行方式

- 地址：`http://127.0.0.1:3000`
- Compose：`/private/tmp/xianyusmart-qa-handoff/compose.yaml`
- 环境文件：`/private/tmp/xianyusmart-qa-handoff/.env`
- 版本：`v2.5.0`
- 数据库：MySQL 5.7.18，Tenant-A
- 账号：`101` 可执行、`102` 部分覆盖、`103` 过期/无可用通道。

测试人员从 QA 环境文件读取管理员凭据，不应把密码、Token 或 Cookie 写入报告、命令历史或截图。

## 六重安全边界

隔离发布只有以下条件同时满足才会执行：

1. `SPRING_PROFILES_ACTIVE=qa`
2. `PUBLISH_QA_MOCK_ENABLED=true`
3. 租户 ID 为 `1`
4. 账号 ID 属于 `101,102,103`
5. 发布通道为 `QA_LOCAL`
6. 标题以 `QA-PUBLISH-` 开头

结果必须同时包含：

- `executionChannel=QA_MOCK`
- `dataSource=QA_FIXTURE`
- `platformNetworkCalls=false`
- `platformWrite=NOT_PERFORMED`

任一边界不满足必须返回 403/409，不得回退到真实平台发布。

## API

- `GET /api/publishing/accounts/{accountId}/capabilities`
- `POST /api/publishing/preflight`
- `POST /api/publishing/execute`
- `GET /api/publishing/requests/{requestId}`

基础载荷示例：

```json
{
  "xianyuAccountId": 101,
  "name": "QA-PUBLISH-独立验收商品",
  "description": "隔离 QA 验收，不调用闲鱼平台。",
  "images": ["https://picsum.photos/seed/xianyu-publish-qa/600/600"],
  "stock": 1,
  "amount": 19.90,
  "publishChannel": "QA_LOCAL",
  "category": "软件工具",
  "deliveryMethod": "线上交付",
  "productType": "VIRTUAL",
  "freeShipping": true,
  "province": "北京市",
  "city": "北京市",
  "district": "东城区",
  "requestId": "qa-publish-由测试生成唯一值"
}
```

## 故障注入

- 默认或 `qaScenario=SUCCESS`：任务完成，生成本地 QA 商品、商品事件和统一审计。
- `qaScenario=LOCAL_PENDING`：返回夹具商品 ID，但本地商品不落库；任务为 `QA_CONFIRMED_LOCAL_PENDING`。
- `qaScenario=UNKNOWN`：任务为 status=4、verification=UNKNOWN、outcome=UNKNOWN，最大尝试次数 1，不自动重发。

## 核心验收

- 预检与执行使用同一业务载荷；改动任一业务字段后旧预检失效。
- 同一 requestId、同一载荷重放返回同一 taskId，并标记 `idempotentReplay=true`。
- 同一 requestId、不同载荷返回 409。
- `amount=0.001`、`1.234`、0、负数和超上限返回 400；两位小数与精确上限通过。
- 账号 103 不得出现可选通道，前端第 3 步“下一步”禁用。
- 请求状态、商品事件和统一审计可用同一 requestId 关联。

## 禁止事项

- 不修改为真实 `QR_COOKIE` 或 `OFFICIAL_OAUTH` 通道执行写入。
- 不在生产店铺验证发布、删除、退款或申诉。
- 不把 QA 夹具成功表述为闲鱼平台发布成功。
