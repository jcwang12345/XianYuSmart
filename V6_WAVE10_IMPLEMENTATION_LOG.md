# V6 Wave 10 实施记录 — 全店立即擦亮

## 版本与范围

- source requirement: `YUMAIDUO_LOCAL_FEATURE_COMPARISON.md` 商品营销 P2“全店立即擦亮”，继承 `V6-PRD-04`、`V6-TASK-01～06` 的安全执行要求。
- scope: 店铺 360 → 当前店铺全部在售商品 → 跨页筛选快照 → 擦亮预检 → 持久化批量任务。
- safety: 只在隔离账号 101 运行预检；没有输入确认文案、没有创建任务、没有触达平台；真实账号 202/203 和现有商品未修改。

## 交付

- 店铺 360 页头新增“全店擦亮”，提示该入口不会直接执行。
- 进入商品矩阵时固定携带账号、`ON_SALE` 状态和 `POLISH_ALL` 意图；不会扩大到全部店铺或已下架商品。
- 自动选择 `POLISH + FILTER_SNAPSHOT` 并立即运行只读预检；动作和范围在专用入口中锁定。
- 复用既有 PRD-04 主子任务、幂等、按店限速、部分成功、结果未知、重启恢复、取消、失败重试、事件、通知与审计，不创建第二套执行模型。
- 预检失败保留弹窗和重试入口；取消会清除 URL 动作意图，刷新不会误重复打开。
- 普通批量操作继续显示“创建任务”，专用入口才显示“创建擦亮任务”。

## 验证

- frontend type check: `scripts/local-toolchain.sh npm --prefix vue-code run type-check` — passed。
- production build: `scripts/local-toolchain.sh npm --prefix vue-code run build-only` — passed，359 modules transformed。
- runtime preview: 账号 101 / 97 件在售商品 / 97 可执行 / 0 冲突；响应明确 `QA Mock，不触达平台`。
- health: 裸机 `http://127.0.0.1:3000`，Docker MySQL `127.0.0.1:13306`。
- final artifact: `.tools/native-qa/releases/xianyusmart-4.0.0-rc.1-20260915T153157Z-46088cd53a5b.jar`。

## Product Design 增量 QA

- desktop: 店铺 360 页头入口与“数据看板、刷新详情”同级，名称直接表达范围；预检首轮发现底部动作被长内容挤出视口。
- fix: 弹窗改为固定标题/底部、正文单滚动；排除列表保留局部滚动。
- desktop recheck: 1280×720 下账号、97 件范围、限速、可执行/冲突、确认输入和底部动作同屏可达。
- mobile recheck: 390×844 单列布局，无页面级横向溢出；正文可滚动，确认输入完整可见，底部取消/创建常驻。
- accessibility: dialog 使用动态 `aria-labelledby`；动作/范围锁定仍有可读标签；创建按钮在完整确认前禁用。

## 已知边界

- 是否可执行仍以逐商品平台能力预检为准；正式账号可能出现部分可执行、冲突或平台未验证，不会因入口名称而绕过能力门禁。
- 粉丝价、小刀活动和闲鱼币抵扣仍缺少可靠平台写适配器，继续只读降级，没有在本批伪装成已实现。
