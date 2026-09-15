# V6 Wave 19 实施记录：发布证据闭环与运行时通道

## 交付范围

- 修复独立验收缺陷 `V6-W18-QA-001～004`：任务账号名称、发布事件字段证据、发布快照完整落库、商品图片失败兜底。
- 发布能力不再只依赖静态通道配置：从当前账号与 Cookie 运行状态投影 `QR_COOKIE / LOCAL_RUNTIME`，分别表达“可预检”和“可执行”。
- 真实账号处于安全验证状态时明确显示扫码续期路径；不会把凭据存在误报成可发布，也不会隐藏该通道。

## 变更文件

- 后端：`PublishCapabilityService`、`MerchantOperationsService`、`ListingDraftService`、`ProductMatrixService`、`GoodsInfoService`、`GoodsInfoServiceImpl`、`PlatformPublishService`、`PublishQaMockService`。
- 前端：`vue-code/src/views/operations/index.vue`、`vue-code/src/views/goods/index.vue`、`vue-code/src/views/product-publish/index.vue`。
- 测试：`PublishCapabilityServiceTest`、`ProductMatrixServiceTest`、`PlatformPublishServiceEditTest`、`PublishQaMockServiceTest`、`GoodsInfoPublishedSnapshotTest`。
- 最终生产静态资源由本批最终前端源码重新生成，未采用独立测试任务的中间构建产物。

## 数据与迁移

- 新增 `V61__repair_verified_publish_snapshot.sql`。
- 对已经具备 `VERIFIED + localSynced=true` 证据的发布事件，回填缺失的库存、类目、发布来源与同步状态；不覆盖已有可靠事实。发布通道由新发布写入逻辑按实际执行通道保存。
- MySQL 5.7 验证：`61/61` 个迁移成功，`installed_rank=61`。
- `QA-PUBLISHED-41` 回填结果：库存 `52`、类目 `OFFICE_PLUGIN / 办公软件与插件`、来源 `SYSTEM_PUBLISH`、通道 `QA_LOCAL`、同步状态 `SUCCEEDED`。

## 接口与行为

- 发布能力响应新增向后兼容字段 `executionAvailable`、`availableChannelCodes`、`executableChannelCodes`。
- 新建发布任务和草稿执行必须选择真正可执行的通道；历史请求幂等重放先返回既有结果，不被当前通道状态错误阻断。
- 商品发布事件兼容 `fieldDiff.fields` 和 `fieldDiff.items`，展示标题、详情、价格、库存、类目、图片的请求值、平台值、状态与说明。
- 发布成功快照同时保存库存、类目、来源、通道和同步状态；隔离 QA 与真实平台的证据来源保持可区分。

## 测试与运行结果

- 后端全量测试：`338 tests / 0 failures / 0 errors / 0 skipped`。
- 前端类型检查通过；Vite 生产构建 `365 modules transformed`。
- 裸机服务：`http://127.0.0.1:3000`，健康检查 `UP`；Docker 仅复用 MySQL `127.0.0.1:13306`。
- 不可变制品：`.tools/native-qa/releases/xianyusmart-4.0.0-rc.1-20260915T212731Z-2acdb478a347.jar`。
- SHA-256：`2acdb478a347606fca90e3086385c8606c3b1f5c090458a01c6dd0e1854f4a01`。

## Product Design 增量 QA

- 桌面：运营任务 `#41` 正确显示 `QA Full Coverage Shop`；商品 360 显示“商品发布”和六项字段核对；损坏图片回退为“图”；账号 202 显示 `QR_COOKIE / LOCAL_RUNTIME / 需要验证` 与重新扫码说明。
- 390×844：运营任务、商品事件和发布通道均完整可读；三页 `document.scrollWidth = clientWidth = 390`，无页面级横向溢出。
- 加载态保留“正在读取”提示，稳定后再显示事实；当前页面控制台 `warning/error = 0`。
- 没有把未同步值显示为 0；没有复制鱼麦多品牌、文案或素材。

## 安全边界与残余降级

- 本批仅使用 Tenant 1、QA 账号 101 和 QA 商品做写入夹具；未调用闲鱼平台写接口。
- 真实账号 202（10599065）和 203（39175043）仅做只读能力展示。两者当前账号状态要求安全验证，因此可见扫码/Cookie 通道但不可执行发布。
- 官方 OAuth 未接入继续明确显示“未接入”，不宣称真实平台高级字段已适配。
- 未执行真实发布、改价、库存、上下架、删除、退款、申诉、消息或外部通知。
