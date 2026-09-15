# V6 Wave 12 实施记录 — PRD-07 商品营销与 PUB-10 安全降级

## 版本与范围

- requirements: `PRD-07` 粉丝价三档、小刀活动、闲鱼币抵扣与擦亮相邻入口；`PUB-10` 推广、曝光、佣金能力探测与安全降级。
- scope: 商品 360 → 营销 → 本地方案、平台观察、证据来源、范围预检、隔离 QA 状态机、商品事件与统一审计。
- safety: 租户 1 的账号 101/102/103 才能使用 `QA_MOCK`；平台网络调用固定为 `false`。真实账号 202/203 不允许通过 QA 通道执行营销写入，本批未修改真实商品。
- migrations: `V58__product_marketing_state.sql`；`V59__separate_marketing_evidence_sources.sql`。

## 后端交付

- `GET /api/product-matrix/accounts/{accountId}/products/{goodsId}/marketing`：返回本地方案、平台观察、方案来源、平台来源、覆盖度、同步时间、协议/余额和能力探测。
- `POST .../marketing/preview`：验证粉丝价最多两位小数且低于原价、小刀价/份数、闲鱼币比例，生成版本绑定的预检令牌、精确确认文案、字段差异、删除档位、冲突和警告。
- `PUT .../marketing/draft`：只保存本地方案；不改写平台观察值及其来源；同请求同载荷幂等，异载荷冲突。
- `POST .../marketing/apply`：仅隔离 QA Mock 可执行；校验版本、预检令牌和完整确认文案；写逐商品事件与统一审计，明确 `platformNetworkCalls=false`。
- SKU 范围同时返回主档声明数、已验证明细数和覆盖状态。`QA-GOODS-0999` 显示 `0 / 4` 与部分覆盖警告，不再把缺失明细冒充 0 个 SKU。
- 推广、曝光归因和佣金没有可靠平台适配器，始终返回 `UNAVAILABLE / LOCAL_GUARD`，不创建点击后才失败的假入口。

## 前端交付

- 商品 360 营销页按粉丝价、小刀、闲鱼币和推广安全降级分卡展示；本地值与平台值并列，不把 `null` 显示为 0 或关闭。
- 配置弹窗支持桌面居中和手机全屏，页头/页脚固定、正文独立滚动；预计让利、SKU 覆盖、删除档位、字段变化、警告和阻塞均在确认前可见。
- 修复输入被 API 数字值回填后调用 `trim()` 的运行时异常。
- 修复嵌套弹窗同时响应 Esc：现在只有最上层弹窗处理 Esc/Tab，关闭后焦点回到原触发按钮，外层商品档案保留。
- QA 静态资源处理改为遵守 `spring.web.resources.static-locations`；`frontend-fast` 生成磁盘产物后可由运行中的 3000 端口直接读取，无需重打 JAR 或重启 Java。

## 运行时验证

- native macOS app: `http://127.0.0.1:3000`；Docker MySQL: `127.0.0.1:13306`。
- V59 于 2026-09-16 01:37:54 成功应用，schema version `v59`。
- 正常预检：粉丝价 18/17/16、小刀 15 元 × 5、闲鱼币 10%，返回 `QA_MOCK`，无平台调用。
- 删除预检：清空老粉价与已购粉价，返回删除 2 个档位与整体覆盖风险提示；未执行。
- 本地草稿：临时将全部粉丝价改为 17.90，页面同时显示“本地 17.90 / 平台 18.00”；随后恢复 18.00。
- 夹具恢复：通过 QA Mock 重放 18/17/16，最终方案来源与平台来源均为 `QA_FIXTURE`，平台值保持一致。

## 验证结果

- targeted backend: `ProductMarketingServiceTest` — 4/4 passed。
- frontend type check: passed。
- production build: passed，365 modules transformed。
- Product Design QA: 默认桌面和 390×844 通过；正常、部分 SKU 证据、长内容、预检、删除警告、嵌套 Esc 与焦点恢复已实测。
- full backend suite: 69 suites / 303 tests / 0 failures / 0 errors / 0 skipped。

## 已知安全降级

- 尚无经验证的闲鱼商品营销写入适配器；真实账号只能保存本地方案和预检，不能宣称平台生效。
- QA Mock 只证明本地状态机、幂等、审计和 UI，不证明闲鱼粉丝价、小刀或闲鱼币接口契约。
- 闲鱼币真实执行仍需协议状态、余额和平台能力均可验证；未知余额显示为 `—`。
- 推广、商城曝光、佣金比例和归因链路未接入，明确禁用。
