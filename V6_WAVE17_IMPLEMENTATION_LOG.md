# V6 Wave 17 实施记录 — 商品改价、库存与导出审计闭环

- 日期：2026-09-16
- 需求：`PRD-03 / P03-03`、`PRD-04` 相邻批量状态机、`AUD-01`、独立测试缺陷 `V6-W16-QA-001`
- 安全范围：只读取租户 1、账号 101 的 `QA-GOODS-*` 隔离夹具；没有创建批量任务，没有触达闲鱼平台，没有改动真实账号 202/203 或任何已发布商品。

## 功能结果

- 商品 360 的“改价”“改库存”不再是静态占位，按商品能力进入现有批量预检、确认、持久化主子任务、幂等、限速、结果未知、取消、重试、通知、事件与审计链路。
- 新增独立 Java 平台编辑适配器：先读取编辑快照，只保留已识别字段，再调用商品编辑接口；价格严格换算为分，库存使用整数；写后最多三次回读，只有精确匹配才确认成功。
- 平台请求成功但回读失败、缺值或不一致时进入“结果未知”，禁止当作失败自动重试；本地价格/库存只在平台确认后更新。
- 多 SKU 商品在缺少逐 SKU 契约时安全阻断，避免把多个规格压平成单 SKU；真实平台库存 0 引导使用下架。隔离 QA Mock 可完整验证任务状态机，但不冒充平台能力。
- 价格校验覆盖 `0.001`、`1.234`、合法两位小数和上限；库存限定为 0～9999 整数。
- 修复 `V6-W16-QA-001`：商品导出按实际账号范围逐账号写入 `PRODUCT_EXPORT` 审计，同一请求 ID 可在账号范围内检索，不再产生 `accountId=null` 的孤立审计。
- Product Design 增量 QA 发现并修复嵌套弹窗 Esc 同时关闭的问题：顶层预检消费 Escape 后停止继续传播，商品 360 保持打开，焦点返回原“改价/改库存”按钮。

## 变更文件与接口

- 平台写守卫：`PlatformWritePolicy`、`RiskControlService`、`RiskControlServiceImpl`。
- 平台适配：`PlatformPublishService` 新增经写后回读确认的价格/库存编辑。
- 状态机：`ProductBatchExecutionService` 路由 `CHANGE_PRICE/CHANGE_STOCK`，记录 before/after/字段差异与请求值。
- 商品能力与审计：`ProductMatrixService` 增加单 SKU/环境能力预检、QA Mock 能力和逐账号导出审计。
- 前端：`vue-code/src/views/goods/index.vue` 接通单品入口并校验价格/库存；`useModalFocusTrap.ts` 修复嵌套弹窗 Escape。
- API 路径不新增：继续使用 `/api/product-matrix/batches/preview`、批量创建/查询接口和商品详情接口；导出接口保持 `/api/product-matrix/products/export`。
- 迁移：无新增迁移。

## 证据、测试与部署

- 平台接口证据只用于确认请求形态；参考了公开项目 `DoLovya/pyxianyu` 的接口名称和字段轮廓，没有复制其 GPL 源码。真实协议仍以写后回读为最终确认。
- 后端全量：329 tests，0 failures，0 errors，0 skipped。
- 前端最终类型检查通过；Vite 7.3.2 生产构建 365 modules，构建完成于最后一次源码修改之后。
- 新增/更新测试：`PlatformPublishServiceEditTest`、`ProductBatchExecutionServiceTest`、`ProductMatrixServiceTest`、`PlatformWritePolicyTest`。
- 裸机 QA：`http://127.0.0.1:3000` 健康；Docker 仅复用 MySQL `127.0.0.1:13306`。
- 不可变制品：`/Volumes/Data/codex/xianyu/.tools/native-qa/releases/xianyusmart-4.0.0-rc.1-20260915T203251Z-e66cacce7a2f.jar`。
- SHA-256：`e66cacce7a2f53e835ba816f1b110ce775e066a96c116a5c989210815250dc62`。

## Product Design 增量 QA

- 1280×720 当前运行视口完成商品 360、改价预检、库存预检、三位小数阻断、QA Mock 边界、Esc 分层关闭和焦点恢复；`scrollWidth=clientWidth=1280`，控制台日志为空。
- 截图保存在 `.artifacts/wave17-design-qa/`：商品 360、改价预检、库存预检及 Esc 保留详情。
- 当前浏览器安全策略拒绝创建窄屏包装页面，因此本批不伪造 390px 动态结论；响应式 CSS 未改变，沿用 Wave 16 已通过证据，并明确要求独立测试在冻结提交上补验 390×844。

## 残余安全降级

- 多 SKU 逐规格改价/改库存尚无可靠平台契约，继续阻断；不能把单 SKU 适配宣称为多 SKU 完成。
- 平台编辑接口属于非官方适配，需在用户新建的测试商品上完成一次真实冒烟验证后，才能扩大到真实通道；写后回读不一致继续进入结果未知。
- 本批未执行生产发布、改价、库存、上下架、删除、发货、退款、申诉或外部通知。
