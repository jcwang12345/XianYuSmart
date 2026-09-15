# V6 Wave 14 实施记录 — 商品发布内容风险预检

## 版本与范围

- requirements: `V6-PUB-04`、`V6-PUB-07`，并相邻落实 `V6-BASE-01/10/15`。
- scope: 商品发布草稿的买家可见文本风险检查、字段级修复建议、发布预检阻断和桌面/手机风险卡片。
- safety: 只使用租户 1、隔离账号 101 与本地草稿 `QA-WAVE14-内容风险校验`；没有执行确认发布，没有访问闲鱼平台，没有改动真实账号 202/203 的商品。
- migrations: 无。风险词继续读取既有系统设置 `product_prohibited_terms`；没有新增表或列。

## 后端交付

- `POST /api/publishing/validate` 在结构校验之外返回 `contentPolicy`：策略版本、来源、检查时间、检查字段数、阻断数、最高严重度、命中词、字段级发现和下一步。
- 检查范围覆盖标题、商品详情、支持政策、售后政策、服务范围、类目属性以及 SKU 规格值，不再只拼接标题和详情后返回一个泛化错误。
- 每项发现包含 `code/severity/field/fieldLabel/term/start/end/snippet/message/suggestion`，可以从界面定位到具体编辑区域。
- 策略版本由当前风险词集合生成稳定指纹；配置发生变化时版本随之变化，方便验收和审计复现。
- `requireValidForPublish` 复用同一校验结果；存在阻断项时返回 400，且不会创建发布预检快照或 `previewToken`。
- `ProductContentPolicyService.validate(null)` 使用空载荷安全处理，避免边界请求出现空指针。

## 前端交付

- 商品发布“风险校验与最终发布”新增独立内容风险卡片，显示阻断数量、策略版本、已检查字段数、命中字段、命中词、上下文片段、修复建议、来源、检查时间和明确下一步。
- 风险发现不再与普通结构错误重复展示；结构缺项保留在“需要修正表单”，内容策略问题集中在专用风险卡片。
- 点击风险项返回对应商品内容、交易承诺、类目或 SKU 编辑区域；手机端同样可定位。
- 风险内容出现时“确认发布”保持禁用，页面不显示或生成预检凭证。

## Product Design QA

- evidence: 继续采用用户提供的鱼麦多参考图中“风险可执行、来源可见、详情不丢上下文”的信息原则，没有复制其品牌、文案或素材。
- 1920×1080: 结构错误与内容风险分层清楚；风险卡与买家预览同屏可核对；页面 `scrollWidth=clientWidth=1920`，没有横向溢出。
- 3840×2160: 表单、右侧买家预览和风险结果在受控工作区内展示，没有拉伸裁切。
- 390×844: 风险卡单列，字段/命中词/片段/建议和来源全部可读；底部保存、校验、确认动作可达；滚轮下滑没有自动回顶。
- interaction: 点击“商品详情 / 站外交易”风险项后准确滚回商品详情输入框；最终控制台 warning/error 为 0。
- evidence limitation: Product Design 浏览器工具只提供本轮内联截图，未提供可提交的 PNG 路径；视口、URL、布局测量与结论记录在 `design-qa.md`。

## 验证结果

- targeted backend: `ProductContentPolicyServiceTest,ListingDraftServiceTest` — 14/14 passed。
- full backend suite: 313 tests / 0 failures / 0 errors / 0 skipped。
- frontend type check: passed。
- production build: passed，365 modules transformed。
- API evidence: 风险详情校验返回 `valid=false`、`blockerCount=1`、`fields=[description]`、无 `previewToken`；同载荷发布预检返回 400 且无 `previewToken`。
- runtime: 裸机 macOS Java，`http://127.0.0.1:3000`；Docker MySQL `127.0.0.1:13306`，health `UP`。
- deployed artifact: `.tools/native-qa/releases/xianyusmart-4.0.0-rc.1-20260915T185027Z-2257772a8eec.jar`。

## 已知安全降级

- 风险词来自本地系统设置，不宣称等同闲鱼实时审核词库；平台规则仍以真实平台预检结果为准。
- 当前只做确定性词条命中，不把语义模型推断冒充平台规则；同义变体和图片文字识别后续需单独适配。
- 检查为发布前只读动作，不把商品正文写入额外审计日志，避免扩大敏感内容留存面。
