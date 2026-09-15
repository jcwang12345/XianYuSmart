# V6 Wave 11 实施记录 — ACC-10 多账号批量动作

## 版本与范围

- requirement: `ACC-10`，跨页选择、范围预检、启用/停用/同步/续期、按账号串行和逐项结果。
- scope: 账号矩阵 → 显式或筛选快照选择 → 只读预检 → 持久化账号子任务 → 真实状态轮询。
- safety: 隔离 QA 只允许租户 1 的账号 101/102/103；禁止与真实账号混合；QA Mock 不触达闲鱼平台。真实账号 202/203 和既有商品未修改。
- migration: 无。复用 `merchant_task`、统一审计和既有账号/凭据模型。

## 后端交付

- `POST /api/account-matrix/batches/preview`：解析跨页筛选快照、排除项和最多 1000 个账号，返回可执行/冲突、范围摘要、执行通道、精确确认文案和状态指纹。
- `POST /api/account-matrix/batches/{operation}/create`：校验动作、预检指纹和确认文案；逐账号落持久任务；同请求同动作幂等回放，不因首次执行后账号状态变化而误拒绝；异载荷复用请求返回冲突。
- `GET /api/account-matrix/batches/{batchId}`：返回批次汇总和逐账号真实任务状态，无模拟进度。
- 调度器按账号顺序领取任务；启停、连接运行态同步和续期分别复用现有服务。SYNC 明确只更新消息连接运行实况，不把店铺画像或处罚快照冒充已同步。
- QA Mock 可验证完整本地状态机；真实续期只创建扫码请求，仍需对应闲鱼 App 完成验证。
- 权限沿用 `ACTION_ACCOUNT_BATCH`；创建批次写统一审计，包含租户、操作者、请求、批次、范围和执行通道。

## 前端交付

- 表格增加逐店/当前页选择，翻页不丢显式选择；可切换为“当前筛选全部”，并保留排除项。
- 选择条持续显示范围，批量弹窗自动预检，展示选中、可执行、冲突、通道和逐店原因。
- 创建前要求显式勾选风险确认；创建后轮询真实后端批次和逐项状态。
- 弹窗桌面居中，手机全屏；固定页头/页脚，正文单独滚动，候选账号列表保留最小可读高度。

## 验证

- backend full suite: 67 suites / 297 tests / 0 failures / 0 errors / 0 skipped。
- targeted regression after idempotency hardening: `AccountBatchExecutionServiceTest,AccountBatchServiceTest` — 7/7 passed。
- frontend type check: passed（本批源码完成后执行）。
- production build: passed，362 modules transformed。
- runtime: native macOS app `http://127.0.0.1:3000`；Docker MySQL `127.0.0.1:13306`；health `UP`。
- Product Design QA: 1280×720 和 390×844 通过；移动端首轮发现候选列表被压缩，补最小高度并重载复核后可读；没有页面级横向溢出。

## 已知安全降级

- 当前 SYNC 不是闲鱼经营数据全量同步，只同步消息连接运行实况；画像和处罚继续显示其自身来源与快照时间。
- 正式续期不能绕过平台安全验证；任务只准备私密二维码并等待对应账号扫码。
- 正式账号的启停会真实影响消息监听、自动回复和自动发货；本轮没有对 202/203 执行创建任务。

## 加速后的交付节奏

- 开发阶段只跑受影响模块和相邻契约；冻结候选时才跑一次全量后端、类型检查、生产构建和部署。
- 浏览器只复核受影响页面族、桌面和手机关键断点；全站巡检集中到里程碑门禁。
- 独立测试使用冻结提交归档并行验收，避免共享工作树和重复机械产物互相覆盖。
- QA 运行时优先读取磁盘前端产物；`scripts/native-qa.sh frontend-fast` 可在不重打约 290MB JAR、不重启 Java 的情况下刷新纯前端修改，冻结版本仍使用完整 `deploy`。
- Java 开发回路使用 `-Dmaven.compiler.useIncrementalCompilation=false` 只编译时间戳变化的源码；本轮末次修正只编译 1 个文件并通过，冻结门禁仍执行全量测试。
