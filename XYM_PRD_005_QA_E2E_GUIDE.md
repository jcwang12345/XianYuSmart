# XYM-PRD-005：PRD-04 隔离 E2E 验收指南

版本：`v2.3.5`
隔离地址：`http://127.0.0.1:12401`
安全对象：Tenant-A、店铺 `101/102/103`、商品 ID 前缀 `QA-`
生产端口 `2000` 和真实平台商品不在测试范围内。

## 1. 安全门禁

隔离执行通道只有同时满足以下条件才会启用：

1. Spring active profile 为 `qa`；
2. `PRODUCT_BATCH_QA_MOCK_ENABLED=true`；
3. 当前租户为显式白名单租户 `1`；
4. 店铺属于 `101,102,103` 且商品 ID 以 `QA-` 开头。

应用启动时会校验上述配置。缺少 profile、租户、店铺或有效商品前缀时直接启动失败。生产默认开关为关闭；`QaProductBatchController` 在非 `qa` profile 下不会注册。隔离任务与平台任务不得混合创建。

验收前先调用：

```bash
curl -H "Authorization: Bearer $QA_TOKEN" \
  http://127.0.0.1:12401/api/qa/product-batches/configuration
```

必须确认 `enabled=true`、`platformNetworkCalls=false`，以及租户、店铺、商品前缀与上述范围一致。

## 2. 创建任务

仍使用正式 PRD-04 API，不存在第二套假任务接口：

- `POST /api/product-matrix/batches/preview`
- `POST /api/product-matrix/batches/{operation}/create`
- `GET /api/product-matrix/batches/{jobId}`
- `POST /api/product-matrix/batches/{operation}/{jobId}/retry`
- `POST /api/product-matrix/batches/{jobId}/cancel`
- `POST /api/product-matrix/batches/{jobId}/failures/export`

预检请求在 `operationParams` 中加入隔离场景，例如：

```json
{
  "requestId": "qa-p04-example",
  "idempotencyKey": "qa-p04-example",
  "operationType": "SYNC",
  "selectionMode": "EXPLICIT_IDS",
  "items": [{"accountId": 102, "goodsId": "QA-GOODS-0001"}],
  "operationParams": {"qaScenario": "SUCCESS", "qaBypassRateLimit": true},
  "maxOperationsPerMinute": 30
}
```

将预检响应的 `confirmationSummary` 和 `previewToken` 原样带入创建请求。预检响应必须显示 `executionChannel=QA_MOCK`，确认文案必须包含“隔离 QA Mock，不触达平台”。

## 3. 可控调度与故障注入

隔离环境已设置 `PRODUCT_BATCH_DISPATCH_ENABLED=false`，因此任务创建后不会被后台定时器抢跑。以下控制接口只接受持久化为 `QA_MOCK` 的任务，并要求 body 含唯一 `requestId`：

- 单步调度：`POST /api/qa/product-batches/{jobId}/dispatch`
- 排空任务：`POST /api/qa/product-batches/{jobId}/drain?maxCycles=2000`
- 制造一个 RUNNING 子项：`POST /api/qa/product-batches/{jobId}/prepare-restart`
- 对该任务执行真实恢复 SQL：`POST /api/qa/product-batches/{jobId}/recover`

支持的 `qaScenario`：

| 场景 | 可验行为 |
|---|---|
| `SUCCESS` | 全部成功，逐件 `QA_MOCK_CONFIRMED` |
| `FAIL_ONCE` | 首次失败，选中失败项重试后成功 |
| `FAIL_ON_ACCOUNT_102` | 多店部分失败及店铺隔离 |
| `MIXED_100` | 后缀 00～79 成功、80～94 首次失败、95～99 结果未知 |
| `UNKNOWN` | 平台结果未知且禁止自动重试 |
| `AUTH_REVOKED` | 执行前权限撤销，子项跳过 |
| `STALE_VERSION` | 预检后版本变化，子项跳过 |
| `THROTTLE` | 使用真实按店限速表；不要设置 `qaBypassRateLimit` |

`qaBypassRateLimit=true` 只用于快速排空 100/1000 件任务；验证限速时必须为 false。

## 4. 已保留的回放证据

隔离库当前保留以下任务，均为 `executionChannel=QA_MOCK`：

| jobId | 证据 |
|---:|---|
| 2 | 单件成功；同幂等键重放仍为同一任务；通知进入 `QA_TEST_SINK` |
| 3 | 100 件三店任务；首次 80 成功/15 失败/5 未知；15 个失败项选择重试后为 95 成功/0 失败/5 未知 |
| 4 | `AUTHORIZATION_REVOKED` |
| 5 | `STALE_PRODUCT_VERSION` |
| 6 | 重启恢复：1 个 `WORKER_RESTART` 未知、1 个成功，`recoveryCount=1` |
| 7 | 三件任务安全取消，3 个子项均 `CANCELLED` |
| 8 | 同店每分钟 1 次；首次成功 1 件，立即再次调度仍有 1 件排队，等待上界 60 秒 |
| 9 | 1000 件筛选快照任务；真实持久化 1000 个子项后安全取消 |

任务 8 特意保持运行中，供独立测试观察限速中间态；可以使用正式取消 API 收尾。

## 5. 证据读取与判定

- 商品时间线：`GET /api/product-matrix/accounts/{accountId}/products/{goodsId}/events`
- 统一审计：`POST /api/operation-log/query`，按 `requestId` 查询
- 任务详情：检查父任务计数、每个子项状态、`attemptCount`、`errorCode`、`platformRequestId`
- 通知：检查 `notificationEvidence.route=QA_TEST_SINK` 且 `externalDispatched=false`
- 平台隔离：成功子项的 `result.platformNetworkCalled=false`，请求 ID 以 `QA-MOCK-` 开头

隔离任务不会调用发布、上下架、删除、擦亮或同步的平台服务，也不会更新商品的本地平台状态。终态通知不会写入企业微信/邮件发件箱；它会持久化为任务通知证据和 `BATCH_NOTIFICATION` 商品事件。

## 6. 本次开发验证结果

- Flyway：36 个迁移校验通过，V36 在 MySQL 5.7.18 成功应用。
- 后端：130 tests，0 failures，0 errors，0 skipped。
- 前端：`vue-tsc --build` 通过；Vite 生产构建 342 modules 通过。
- API：五种动作 `SYNC/ON_SALE/OFF_SHELF/POLISH/DELETE` 均可在 QA Mock 预检且 `executableCount=1`。
- 数据库：job 3 共 100 子项；重试后 95 成功、5 未知、15 项 `attemptCount=2`。job 9 共 1000 子项并安全取消。
- 事件与审计：QA Mock 商品事件 112 条、覆盖 8 个任务；`qa-005-*` 统一审计 23 条。
- 外部通知：job 2/3/9 的通知证据均为 `QA_TEST_SINK`、`externalDispatched=false`；对应外部通知 outbox 记录为 0。
