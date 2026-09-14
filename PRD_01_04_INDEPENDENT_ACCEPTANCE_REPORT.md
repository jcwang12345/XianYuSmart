# XianYuSmart v2.3.0 商品专项独立验收报告

## 1. 结论

- 初始验收基线：`v2.3.0` / `24bd766ab7b6c3048b5e876cb2a4054b0fb08265`；最新增量回归：`v2.3.1` / `515950fe7d9eecb435bf678bbf15519056402c3b`
- 隔离环境：`http://127.0.0.1:12401`，MySQL 5.7.18；健康接口 `UP`，版本接口 `2.3.0`
- 总体结论：**不通过，禁止出具最终回归完成声明**。
- 缺陷状态：`XYM-PRD-001` 已在 v2.3.1 关闭；`XYM-PRD-002～006` 仍未关闭，其中 002～004、006 为功能缺陷，005 为 PRD-04 安全端到端可验性阻塞。
- 安全边界：未访问生产端口执行写操作；未执行发布、上/下架、改价、改库存、删除、退款、发货、申诉或外部通知。
- 状态定义：`PASS`=已取得运行证据；`PARTIAL`=仅部分链路或只读证据；`SAFE-DEGRADED`=明确不可用且没有伪成功；`FAIL`=确认缺陷；`BLOCKED`=缺少安全测试路径或浏览器能力。

## 2. 门禁证据

| 门禁 | 结果 | 独立证据 |
|---|---|---|
| Git 基线 | PASS | `git rev-parse` 与标签均指向 `24bd766...` / `v2.3.0` |
| 运行健康/版本 | PASS | `/actuator/health`=`UP`；`/api/system/version`=`2.3.0` |
| 后端测试 | PASS | 从 `git archive v2.3.0` 干净快照运行，114/114，0 failure/error/skipped |
| 前端类型与生产构建 | PASS | 同一干净快照，`vue-tsc` 成功，Vite 337 modules 构建成功 |
| MySQL 5.7 迁移 | PASS | `flyway_schema_history` 35 条，max=35，success=35，failed=0；最新为 V35 |
| P0/P1 清零 | FAIL | 5 个 P1 未关闭，最终门禁不满足 |

说明：共享工作区中开发任务正在处理 `XYM-PRD-001`，已出现未提交修改。为避免污染基线，上述编译测试均从标签快照重跑；开发中修改不计入本报告通过证据。

## 3. PRD-01 P01-01～14

| 用例 | 结果 | 证据/说明 |
|---|---|---|
| P01-01 状态分栏 | PASS | ALL=1000；ON_SALE/SOLD/OFF_SHELF/OTHER 各 250；DRAFT=0，计数与列表一致 |
| P01-02 搜索 | FAIL | goodsId、标题、首尾空格和无结果正常；完整 outerId `QA-OUTER-1` 返回 111 条且目标不在首屏，见 `XYM-PRD-006` |
| P01-03 组合筛选 | PASS | account=101 + OTHER + MANUAL_IMPORT + QA_MOCK 返回 84 且逐条一致；group=101 返回 334 且仅账号 101 |
| P01-04 店铺权限 | PASS | operator 对 101=200、102=403；Tenant-B 猜测 Tenant-A 商品=404；support=403；匿名=401 |
| P01-05 1000 商品 | PARTIAL | API 首屏约 0.06s、总数与分页正确；浏览器滚动与交互性能未实测 |
| P01-06 分栏计数 | PASS | 全量及账号 101 各分栏合计与总数一致 |
| P01-07 汇总口径 | PASS | `summaryScope=FILTERED_RESULT`；未同步经营指标为 null/UNSYNCED，不伪造 0 |
| P01-08 图片失败 | BLOCKED | 当前无浏览器截图能力；仅代码可见 `@error` 占位，不作为界面通过证据 |
| P01-09 慢请求 | BLOCKED | 为避免中断开发任务正在使用的共享 QA 容器，未执行 pause；仅代码可见保留旧列表的 loading 状态 |
| P01-10 接口失败 | BLOCKED | 同上，未停止共享 MySQL；仅代码可见持久错误与重试入口 |
| P01-11 空/未同步 | PASS | 无结果 total=0；账号 103 与指标窗口返回 UNSYNCED/null，没有把缺失显示成 0 |
| P01-12 响应式 | BLOCKED | 缺少当前构建的 1366×768、1440×900、390×844、200% 截图证据 |
| P01-13 键盘焦点 | BLOCKED | 缺少浏览器 Tab/Shift+Tab/Esc/焦点恢复实测；静态 focus-trap 不计通过 |
| P01-14 相邻回归 | PARTIAL | 详情开关前后列表 API、筛选和分页数据未变化；浏览器上下文保持未实测 |

## 4. PRD-02 P02-01～14

| 用例 | 结果 | 证据/说明 |
|---|---|---|
| P02-01 基本信息 | PASS | QA-GOODS-0999 返回商品、店铺、来源、通道、覆盖度及空值语义 |
| P02-02 多 SKU | PASS | v2.3.1 回归：QA-GOODS-0864 为 4/4 FULL，价格、库存、平台状态及 4 条履约映射完整；`XYM-PRD-001` 已关闭 |
| P02-03 无 SKU | PARTIAL | 页面文案不会把缺少子项说成平台 0 SKU；基线缺少“完整快照确认无 SKU”的独立证据 |
| P02-04 自动发货映射 | SAFE-DEGRADED | 没有真实 SKU/履约夹具，未伪造已配置状态 |
| P02-05 粉丝价 | SAFE-DEGRADED | 平台营销字段 null/UNSYNCED，界面明确不提供假配置入口 |
| P02-06 小刀/闲鱼币 | SAFE-DEGRADED | 同上，明确平台适配器未接入 |
| P02-07 1/7/30 天 | PASS | 三窗口返回独立 windowDays；未同步指标均为 null、coverageStatus=UNSYNCED |
| P02-08 时间线 diff | FAIL | `XYM-PRD-002`：本地编辑事件仅有 `{mode:LOCAL_ONLY}`，缺字段旧值/新值 |
| P02-09 Webhook 去重 | BLOCKED | 未提供安全可重放 Webhook 夹具，不能仅凭静态唯一键判定通过 |
| P02-10 原始快照 | PARTIAL | admin 可读 HASH_ONLY/redacted 元数据；operator=403；隔离数据没有真实脱敏快照内容可核对 |
| P02-11 长文本/50 SKU | PARTIAL | v2.3.1 的 QA-GOODS-0960 为 50/50 FULL 且 50 条履约映射完整，生产构建通过；独立浏览器滚动/布局仍未实测 |
| P02-12 状态矩阵 | PARTIAL | API 已覆盖成功、空、部分、未同步、无权限；页面加载/失败视觉状态阻塞 |
| P02-13 响应式详情 | BLOCKED | 无当前浏览器截图与键盘实测能力 |
| P02-14 相邻回归 | PARTIAL | 本地编辑后列表 rowVersion/syncStatus 和详情时间线更新；通知链路未验证 |

## 5. PRD-03 P03-01～14

| 用例 | 结果 | 证据/说明 |
|---|---|---|
| P03-01 编辑支持通道 | SAFE-DEGRADED | 当前没有已验证的平台编辑适配器，不声明平台成功 |
| P03-02 不支持通道 | PASS | 本地编辑返回 `LOCAL_ONLY/LOCAL_SUCCESS`，界面明确“仅本地” |
| P03-03 改价/库存校验 | FAIL | 负价格、0 价格、负库存被拒；rowVersion 冲突=409；但 `1.234` 被接受，见 `XYM-PRD-004` |
| P03-04 上下架状态机 | SAFE-DEGRADED | QA 店铺未启用，预检全部冲突；没有执行平台写入 |
| P03-05 擦亮 | SAFE-DEGRADED | QA 店铺未启用且无平台凭据，预检明确冲突 |
| P03-06 删除 | PARTIAL | 预检含精确店铺/商品与“删除不可恢复”，权限层有独立 DELETE 权限；未执行删除 |
| P03-07 超时未知 | PASS | 精确基线单测确认平台超时持久化 UNKNOWN、无自动重试；预置任务含 UNKNOWN/platformRequestId |
| P03-08 本地待修复 | PASS | 精确基线单测确认平台成功、本地更新失败为 `PLATFORM_CONFIRMED_LOCAL_PENDING` |
| P03-09 自动化配置 | FAIL | `XYM-PRD-003`：保存返回 500，日志为 `xianyu_auto_rate_content` 无默认值 |
| P03-10 细粒度权限 | PASS | admin/operator/support/Tenant-B/匿名 API 与店铺范围符合预期；高危权限后端独立拦截 |
| P03-11 审计 | FAIL | `XYM-PRD-002`：操作审计有操作者、账号、请求 ID、结果层，但缺编辑旧值 |
| P03-12 双击/并发 | PARTIAL | 两次使用旧 rowVersion 第二次=409；平台幂等写入因安全边界未执行 |
| P03-13 错误恢复 | BLOCKED | 表单保留与安全重试提示需浏览器故障注入实测 |
| P03-14 相邻回归 | PARTIAL | 本地编辑后 PRD-01/02 数据一致；站内通知未验证 |

## 6. PRD-04 P04-01～22

| 用例 | 结果 | 证据/说明 |
|---|---|---|
| P04-01 当前页选择 | BLOCKED | 需浏览器交互实测 |
| P04-02 跨页全选 | PARTIAL | FILTER_SNAPSHOT 1000 件排除 1 件后 selectedCount=999，非前端拼接 ID；UI 未实测 |
| P04-03 切换筛选 | BLOCKED | 代码会清空选择并提示，但无浏览器证据 |
| P04-04 多店任务 | BLOCKED | 999 件预检覆盖 3 店；无安全可执行任务验证单店失败隔离 |
| P04-05 预检 | PASS | 各动作均显示数量、店铺、旧值、冲突原因；price/stock 边界缺陷另见 004 |
| P04-06 重复提交 | BLOCKED | 只有 Mock 持久化单测，未提供可安全创建并重放的 E2E 路径，见 005 |
| P04-07 100 件部分失败 | BLOCKED | 预置任务仅 3 件，不满足 100 件与选中失败项重试验收，见 005 |
| P04-08 平台限流 | BLOCKED | 返回速率/预计等待字段；没有安全执行证据，见 005 |
| P04-09 超时未知 | PASS | 单测与预置 UNKNOWN 子项均证明不自动重试，并保留 platformRequestId |
| P04-10 服务重启 | PARTIAL | 单测确认 RUNNING 子项转 UNKNOWN、剩余 QUEUED 重排；未做持久库重启 E2E |
| P04-11 任务取消 | PARTIAL | 单测确认只取消 QUEUED 并保留已完成汇总；无 API E2E |
| P04-12 失败重试 | BLOCKED | 无安全可操作失败任务验证只重试选中项，见 005 |
| P04-13 权限撤销 | PARTIAL | 单测确认执行前重验并 SKIPPED，且不调用平台；无 API E2E |
| P04-14 商品被修改 | PARTIAL | 单品 rowVersion=409；批任务版本变化仅代码路径，缺 E2E |
| P04-15 删除任务 | PARTIAL | 强确认文案、影响范围和权限路径可见；按安全边界未创建/执行删除任务 |
| P04-16 查询隔离 | PASS | operator 详情仅账号 101；Tenant-B 列表为空、猜测 jobId=1 返回 404 |
| P04-17 导出 | PASS | jobId=1 导出 2 条 FAILED/UNKNOWN，无敏感字段；请求 `qa-p04-export-20260914` 产生 PRODUCT_BATCH_EXPORT 审计 |
| P04-18 通知 | BLOCKED | 无可安全完成的任务触发通知，见 005 |
| P04-19 时间线/审计 | BLOCKED | 预置任务三个商品时间线为空，`qa-ui-states` 统一审计为 0；真实执行代码会写事件但缺 E2E，见 005 |
| P04-20 大任务 | BLOCKED | 999 件预检成功；没有 1000 件持久任务页面与请求频率浏览器证据 |
| P04-21 响应式/键盘 | BLOCKED | 无当前构建的桌面、390px、200% 和键盘实测证据 |
| P04-22 PRD-01～03 回归 | FAIL | PRD-02/03 仍有 001～004；不能判相邻链路通过 |

## 7. 缺陷与回传记录

### XYM-PRD-001（P1）多 SKU 主档与子项真值矛盾

- 环境：v2.3.0、Tenant-A、账号 101、QA-GOODS-0864。
- 步骤：读取商品详情并比较 `basic.skuCount` 与 `skus`；只读核对 SKU 表。
- 预期：声明 4 SKU 时返回 4 个可验证子项及履约映射。
- 实际：`skuCount=4`、`skus=[]`，租户 1 SKU 表总数为 0。
- 影响：P02-02、P02-11 阻塞。
- 回传消息：`01a09e74-e2d4-7060-a785-ef0eaa79c97f`；请求 ID：N/A（GET）。
- 状态：**v2.3.1 回归通过，关闭**。4/4 FULL、50/50 FULL、0/0 EMPTY_VERIFIED、4/0 UNSYNCED 均与数据库一致；双向跨租户详情均 404。

### XYM-PRD-002（P1）编辑时间线和审计缺旧值/新值

- 环境：v2.3.0、QA-GOODS-0999。
- 步骤：以 `qa-p03-local-edit-20260914` 保存本地编辑，查询详情时间线和统一操作审计。
- 预期：逐字段旧值、新值、操作者、账号、请求 ID、本地/平台结果齐全。
- 实际：事件 `fieldDiff` 仅 `{mode:LOCAL_ONLY}`；审计请求/响应只有新值，没有旧值。
- 回传消息：`01a09e75-bea6-7381-aa31-47c8331b33be`。

### XYM-PRD-003（P1）自动化配置保存 500

- 步骤：对 QA-GOODS-0999 保存并回读自动化开关。
- 预期：保存成功、回读一致、产生审计/时间线；重复请求幂等。
- 实际：首次、重复及恢复请求均 500；容器日志为 `Field xianyu_auto_rate_content does not have a default value`。
- 请求 ID：`qa-p03-auto-idem-20260914`、`qa-p03-auto-error-20260914`。
- 回传消息：`01a09e76-a1ea-74f3-abe9-74457960bc23`。

### XYM-PRD-004（P1）批量改价未限制货币精度

- 步骤：只读预检 CHANGE_PRICE，price 分别为 -1、0、1.234。
- 预期：负数、0、超过两位小数均拒绝。
- 实际：-1/0 返回 400；1.234 返回 200 和 previewToken。
- 请求 ID：`qa-preview-CHANGE_PRICE-1_234`。
- 回传消息：`01a09e7b-7898-79a2-9f27-75ca5f0e9689`。

### XYM-PRD-005（P1）PRD-04 缺安全 E2E 验收路径

- 步骤：对 QA 商品执行全部动作预检，并查询预置任务与标签测试。
- 预期：隔离 Mock 执行通道或完整可回放夹具能覆盖幂等、部分失败、取消、重试、权限撤销、版本冲突、通知、事件和大任务。
- 实际：所有 QA 商品 `executableCount=0`；预置任务仅 3 个直接终态，且无商品事件/创建审计；只能得到 Mock 单测证据。
- 请求 ID：`qa-preview-SYNC-na`、`qa-filter-preview-20260914`；任务 `PB-QA-UI-STATES`。
- 回传消息：`01a09e82-7b5a-7a10-8edd-162bdd82b12d`。

### XYM-PRD-006（P1）完整 outerId 搜索不能精确定位商品

- 环境：12401 当前候选版接口返回 2.3.1；仓库正式基线仍为 v2.3.0/24bd766。
- 步骤：先确认 QA-GOODS-0001 的 outerId 为 `QA-OUTER-1`，再以该完整 outerId 查询商品矩阵。
- 预期：完整唯一 outerId 精确命中或至少将目标置于首位。
- 实际：total=111，首屏均为其他商品，QA-GOODS-0001 不在首屏。
- 请求 ID：N/A（只读查询）。
- 回归建议：goodsId/outerId 完整值优先精确匹配，标题维持模糊；覆盖相似前缀、大小写、空格及分页。
- 回传方式：应用内任务消息已发送开发任务。

## 7.1 v2.3.1 正式增量回归

- 固定版本：commit/tag 均为 `515950fe7d9eecb435bf678bbf15519056402c3b` / `v2.3.1`；运行镜像 `sha256:7aa1ee4b2228563577d0f47b6085889b790423fa14fc3ebd021a21d4fa138dfa`，healthy，版本接口 2.3.1。
- `XYM-PRD-001`：正式回归通过并关闭。QA-GOODS-0864=4/4 FULL、QA-GOODS-0960=50/50 FULL、QA-GOODS-0000=0/0 EMPTY_VERIFIED、QA-GOODS-0999=4/0 UNSYNCED；API 与数据库行数、库存及履约映射一致。
- 跨租户：Tenant-A 猜测账号 201、Tenant-B 猜测账号 101 均返回 404；Tenant-B SKU 表无夹具泄漏。
- 干净标签快照：ProductMatrixServiceTest 7/7；vue-tsc 与 Vite 337 modules 生产构建通过。
- `XYM-PRD-002`：请求 `qa-reg-002-edit-20260914` 仍只有 `{mode:LOCAL_ONLY}`，失败；测试商品已用 `qa-reg-002-restore-20260914` 恢复。
- `XYM-PRD-003`：请求 `qa-reg-003-20260914` 仍返回 500，失败。
- `XYM-PRD-004`：请求 `qa-reg-004-1.234` 仍返回 200 和 previewToken，失败。
- P02-11 的 50 SKU 数据量和生产编译已通过；由于当前独立测试会话没有浏览器自动化/截图能力，桌面与 390×844 内部滚动仍保持 BLOCKED，不以静态 CSS 代替视觉证据。

## 8. 已知限制与后续回归范围

1. 平台编辑、改价、改库存、上/下架、擦亮、删除、营销同步均按安全降级或隔离冲突处理；本轮没有把这些能力写成“已实现通过”。
2. 当前会话没有可用浏览器自动化/截图能力。按照 Product Design 审计的截图优先规则，响应式、缩放、键盘、焦点、错误恢复和大任务页面交互均保持 BLOCKED，不能以 CSS/组件代码代替证据。
3. 开发任务确认修复后，应在新的已提交、已部署版本上回归 001～005，并覆盖相邻链路：商品列表计数与状态、详情 SKU/时间线、自动化保存、批量预检/任务中心、审计与通知。
4. 只有新版本的 P0/P1 清零，并重新通过后端、前端构建、MySQL 5.7 迁移及浏览器 QA，才可签署最终回归完成声明。
