# V6 Wave 16 实施记录 — 商品逐日趋势与经营报表

## 版本与范围

- requirements: `V6-PRD-06`，补齐商品 360 的 30 天逐日趋势、当前筛选商品经营报表，并修复独立测试缺陷 `V6-W15-QA-001`。
- evidence: 沿用鱼麦多评审证据中“趋势、筛选范围、来源与同步时间可见”的成熟原则，不复制对方品牌、文案或素材。
- scope: 商品逐日曝光人数、详情访客、咨询买家、支付买家、支付金额、退款金额；当前筛选商品经营数据 CSV。
- safety: 只读取租户 1、账号 101、`QA-GOODS-*` 数据。冲突验收临时行已删除；没有调用平台，没有执行发布、上下架、改价、退款、删除或申诉。

## 数据模型与迁移

- migration: none。本批复用 V60 的 `xianyu_goods_metric_daily` 日粒度事实表。
- 每个自然日没有样本时保持 `NULL/—`；同一自然日出现多个来源时标记 `CONFLICT/MULTIPLE` 并隐藏该日数值。

## 后端与 API

- `GET /api/product-matrix/accounts/{accountId}/products/{goodsId}` 新增 `metricTrend`：
  - 固定返回最近 30 个自然日，缺失日期保留空值；
  - 返回 `knownDays`、`conflictDays`、`coverageStatus`、`source`、`lastSyncedTime` 和每个点自身来源；
  - 同日多来源不求和、不选择任意一行，统一返回冲突和空值。
- `POST /api/product-matrix/products/export` 导出当前筛选全部商品，不受当前分页限制；新增统计窗口、曝光/访客/咨询/支付/完成/退款买家、订单、金额、覆盖、来源和同步时间列。
- 导出采用一次分组查询，不对每件商品逐条请求；未知或混合来源指标留空，真实 0 保留为 0。
- 导出审计记录包含筛选范围、统计窗口、导出数量和 `unknownMetricValuesRemainBlank=true`。
- `V6-W15-QA-001`：1/7/30 天窗口 SQL 增加“样本行数是否大于自然日数”的冲突判断。任一自然日存在多来源时，整个窗口的汇总值、漏斗节点和转化率全部隐藏，返回 `valueAvailability=SOURCE_CONFLICT` 和可执行解释，避免把重复来源的求和结果继续暴露给用户。

## 前端与 Product Design

- 商品 360 → 数据罗盘新增 30 天逐日趋势卡，可切换六个经营指标；趋势点显示自然日、值、覆盖和来源。
- 缺失点显示“—”而非 0；同日来源冲突显示“来源冲突”，并隐藏图形数值。
- 商品列表顶部新增“导出当前筛选”，下载文件名包含统计窗口；导出过程中按钮显示明确忙碌态。
- 指标窗口出现来源冲突时，在漏斗和辅助指标之前显示醒目说明，不让用户把缺失值误解为真实 0。
- 1920×1080：趋势、来源、覆盖和固定操作区层级清楚；真实鼠标滚轮连续下滚后保持当前位置，没有自动回顶。
- 3840×2160：档案宽度受控，趋势图没有无限拉伸或裁切。
- 390×844：页签、趋势图和底部操作栏使用各自局部横向滚动；正文保持单列可读，页面主体不被趋势图撑宽。
- 状态覆盖：完整、部分、未同步、来源冲突、加载、空数据、无权限/错误、347 件大数据列表。
- Product Design 浏览器只提供会话内联截图，没有持久化 PNG 路径。

## 验证结果

- targeted backend: `ProductMatrixServiceTest` — 35/35 passed。
- full backend suite: 321 tests / 0 failures / 0 errors / 0 skipped。
- frontend type check: passed。
- production build: passed，365 modules transformed。
- API conflict evidence: `QA-GOODS-0000` 临时同日双来源时，day1 返回 `MULTIPLE / SOURCE_CONFLICT`，曝光人数、支付金额和漏斗值均为 `null`；趋势当天返回 `CONFLICT / MULTIPLE` 和空值。
- API cleanup evidence: 删除临时 `QA_CONFLICT` 行后，day1 恢复 `QA_FIXTURE / AVAILABLE`，曝光人数 720、支付金额 29.90；趋势 `conflictDays=0`。
- export E2E: MySQL 5.7 隔离库按账号/关键词筛选成功导出；`QA-GOODS-0000` 的 7 天行保留真实指标、2 天样本、`QA_FIXTURE/PARTIAL`，未同步商品的经营字段为空而非 0。
- runtime: 裸机 macOS Java，`http://127.0.0.1:3000`；Docker MySQL `127.0.0.1:13306`。
- deployed artifact: `.tools/native-qa/releases/xianyusmart-4.0.0-rc.1-20260915T194407Z-e40f935a89fe.jar`。

## 已知安全降级

- 平台商品经营指标适配器仍未接入；正式账号保持 `NULL/—`，系统不会用订单推断平台 UV。
- 当一个窗口中任一自然日存在来源冲突时，为保证真实性，整窗聚合暂不展示；未来接入权威来源优先级后才可恢复计算。
- CSV 是本地经营证据导出，不会触发平台写入。
