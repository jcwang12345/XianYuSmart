# V6 Wave 15 实施记录 — 商品 1/7/30 天买家漏斗

## 版本与范围

- requirements: `V6-PRD-06`，补齐商品 360 数据罗盘的单品经营漏斗，并相邻回归 `V6-PRD-01/02`。
- evidence: `YUMAIDUO_PRODUCT_MANAGEMENT_REVIEW.md` 第 4 节及 P1 建议；采用 1/7/30 天、PV/UV 分离、来源与覆盖可见的产品原则，不复制对方品牌、文案或素材。
- scope: 曝光人数、详情访客、咨询买家、支付买家、完成买家、退款买家，支付/退款订单和金额，以及逐段转化证据。
- safety: 只向租户 1、账号 101、`QA-GOODS-*` 写入 `QA_FIXTURE` 本地指标；`platformNetworkCalls=false`。没有执行发布、上下架、改价、退款、删除或申诉，没有修改真实账号 202/203 的商品。

## 数据模型与迁移

- migration: `V60__product_metric_buyer_funnel.sql`。
- `xianyu_goods_metric_daily` 新增 nullable 字段：`exposure_uv_count`、`inquiry_buyer_count`、`paid_buyer_count`、`completed_buyer_count`、`refund_buyer_count`、`refund_order_count`、`refund_amount`。
- 所有新列允许 `NULL`；`NULL` 固定表示数据源未同步，不能解释为 0。
- MySQL 5.7.18 隔离库已迁移到 V60，`flyway_schema_history.version=60` 且 `success=1`。

## 后端与 API

- 既有商品详情接口 `GET /api/product-matrix/accounts/{accountId}/products/{goodsId}` 的 `metrics.day1/day7/day30` 新增：
  - 买家漏斗 `funnel.stages`；
  - 逐段转化 `funnel.transitions`；
  - 来源、公式、覆盖、完整/部分/不可计算/不可用状态、原因和单调性异常提示；
  - 新增退款订单、退款金额及逐字段定义。
- 转化率只在上下游值均已同步、来源唯一且覆盖天数兼容时计算。部分窗口可计算时明确标注 `PARTIAL`；上游真实为 0 时返回 `NOT_COMPUTABLE`，不会显示无穷或虚假 0%；下游大于上游时保留原值并发出异常警告，不静默截断。
- “完成买家”和“退款买家”均从“支付买家”分支计算，避免把退款错误解释为完成后的必经阶段。
- 字段覆盖天数按自然日去重计数，同一日存在多来源行时不会把覆盖天数重复累加；多来源仍按不可用处理。
- `QaBusinessAnalyticsController` 的 QA 夹具同步生成完整买家漏斗字段，继续受 qa profile、显式开关、租户、店铺和商品前缀白名单约束，最多 100 条且不调用平台。

## 前端与 Product Design

- 商品 360 → 数据罗盘按 1/7/30 天分别展示买家漏斗，核心链路为曝光 → 详情 → 咨询 → 支付，支付后分为完成和退款两项结果。
- 每个节点显示人数和覆盖天数；每条转化显示百分比及“完整口径 / 部分覆盖 / 证据不足 / 不可计算”。
- PV、订单、支付金额、退款订单和退款金额放在辅助指标区，避免与 UV 买家漏斗混用。
- 空状态继续展示检查范围、最后检查时间和下一步；平台适配器未接入时明确安全降级，不把未同步数据显示为 0。
- 1920×1080：完整漏斗、辅助指标、证据时间和固定动作区同屏可读；页面 `scrollWidth=clientWidth=1920`。
- 3840×2160：档案维持居中受控宽度，核心漏斗无拉伸、裁切或横向溢出。
- 390×844：漏斗改为纵向链路，完成/退款分支和辅助指标单列/双列适配；`document` 与档案均为 390px，无页面级横向溢出。
- 滚轮复核：档案正文从 `scrollTop=0` 滚动至 `844` 后保持 `844`，没有自动回顶。
- 最终浏览器控制台 warning/error 为 0；浏览器工具只提供会话内联截图，未提供可提交 PNG 路径。

## 验证结果

- targeted backend: `ProductMatrixServiceTest,QaBusinessAnalyticsControllerTest` — 33/33 passed。
- full backend suite: 317 tests / 0 failures / 0 errors / 0 skipped。
- frontend type check: passed。
- production build: passed，365 modules transformed。
- migration: V60 在 MySQL 5.7.18 执行成功；七个新增列存在。
- API full evidence: `QA-GOODS-0000` 最近 1 天为 `FULL / QA_FIXTURE`，人数 `720 → 180 → 2 → 1`，完成 0、退款 1，转化 `25.00% / 1.11% / 50.00% / 0.00% / 100.00%`。
- API partial evidence: 最近 7 天对不兼容覆盖返回 `UNAVAILABLE`，兼容的 1/7 天段返回 `PARTIAL`，没有生成伪造完整转化率。
- API empty evidence: `QA-GOODS-0999` 三个窗口均为 `UNSYNCED`，所有漏斗值和转化率为 `null`，界面显示“—”和核对建议。
- runtime: 裸机 macOS Java，`http://127.0.0.1:3000`；Docker MySQL `127.0.0.1:13306`，health `UP`。
- deployed artifact: `.tools/native-qa/releases/xianyusmart-4.0.0-rc.1-20260915T191952Z-2e9352468a69.jar`；最终静态资源已同时封装进 JAR，并继续支持同一工作树的磁盘资源优先加载。

## 已知安全降级

- 平台商品经营指标适配器仍未接入；正式账号的新字段保持 `NULL/—`，不会以 QA 数据或订单数代替买家 UV。
- 当前日表保存数据源提供的“日去重人数”；跨日聚合是日 UV 累计，不宣称为周期内绝对去重人数，字段定义和页面均明确该口径。
- 多来源或上下游覆盖天数不一致时不计算转化率；需要未来的平台同步适配器保证同源同窗后才能形成正式经营结论。
