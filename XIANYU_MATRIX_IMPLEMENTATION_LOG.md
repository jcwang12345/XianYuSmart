# XianYuSmart 矩阵需求实施记录

基线：`XIANYU_MATRIX_PRODUCT_REQUIREMENTS.md` 1.0（2026-09-13）
原则：以需求编号、优先级和验收标准为准；保留既有工作树；禁止以 `0` 代替未同步数据；禁止对生产店铺执行发布、退款、删除或申诉验证。

## 缺陷批次：XYM-IM-001（v2.7.1）

- 根因：MySQL Connector 在不同 affected-row 配置下，`ON DUPLICATE KEY UPDATE` 的无操作更新可能仍返回 1，不能据此区分首次创建与幂等重放。
- 修复：V44 为接管任务增加内部创建尝试标识；服务端持久化后显式比较本次标识与记录标识，首次返回 `false`，重放/并发非赢家返回 `true`，且不向 API 暴露内部标识。
- 回归：覆盖驱动即使对重复写返回 1 的单测；隔离 MySQL 5.7 复测同 requestId 首次/重放、任务唯一和审计唯一。

## 批次 11：IM-01～06 集成客服与 AI 待接管（v2.7.0）

- 需求基线：新增 `IM_01_06_ACCEPTANCE.md`，覆盖双收件箱、可靠发送、AI 转人工、商品知识、回复可解释和响应式客服效率；长期计划以该文件作为 Wave 3 硬门禁。
- 数据模型：V43 为自动回复记录增加决策状态、置信度、模型、耗时和接管原因；新增持久化 AI 接管主表；会话保存接管状态、原因、任务与创建时间。
- 后端：无策略、无安全答案、低置信、AI 不可用、敏感/人工意图和结果未知均可形成去重任务；认领、解决、忽略均校验租户/店铺/动作权限并写统一审计；自动回复调度遇到未决人工任务停止发送。
- 可靠发送：`INSERT IGNORE` 建立唯一发送尝试，并发同 requestId 回读赢家；只有平台确认才写已发送消息；UNKNOWN 建立人工核对任务且禁止自动重发。
- 前端：集成客服升级为买家会话、通知消息、AI 待接管三类收件箱；会话筛选走服务端；接管任务展示原因、优先级、置信度、模型、请求 ID、认领和处理结果。
- Product Design：窄屏右侧上下文由“隐藏”改为可展开区，商品、自动回复、买家、订单和会话运营全部可达；补齐无权限、空、加载、失败、部分失败和旧数据保留提示。
- 安全 QA：新增 `/api/qa/message-workspace/fixtures`，复用 qa profile + 显式开关 + 租户 + 店铺/商品前缀四重白名单；场景明确闲鱼与 AI 网络调用均为 0。
- 迁移验证：macOS 裸机应用连接 Docker MySQL 5.7，Flyway 从 V42 实际应用 V43 成功；生产 2000 和生产数据卷不变。
- 动态验证：LOW_CONFIDENCE 与 OUTCOME_UNKNOWN 夹具建立成功；列表按紧急优先级排序；前端完成认领、解决、处理说明持久化和结果未知警示验证。
- 工程门禁：Data 分区 JDK/Node 执行 Maven 全量 178 项零失败；Vue 类型检查通过；Vite 352 模块生产构建通过；最终静态资源由本提交源码重新生成。
- Product Design QA：1440×900 三栏与 AI 任务中心、390×844 纵向会话和可展开资料、筛选空态、加载态、Cookie 失败且保留旧数据、最小权限导航及大消息样本均完成实测；长 ID 横向溢出已修复，桌面 `scrollWidth=clientWidth=1440`，手机页面滚动不回弹。浏览器截图不能替代完整自动化无障碍扫描，键盘与读屏仍交由独立验收补证。
- 最终部署：裸机 `http://127.0.0.1:3000` 运行 XianYuSmart 2.7.0（Java 21），Docker 仅运行隔离 MySQL 5.7 `127.0.0.1:13306`，schema 为 V43。

## 界面缺陷：鼠标滚轮回弹（v2.6.1）

- 根因：`AppLayout.vue` 的 scoped `main` 样式会同时命中作为子组件根节点的页面 `<main>`，造成内外两层 `overflow:auto`；内层实际没有滚动余量，却用 `overscroll-behavior-y: contain` 截断滚轮向外层的滚动链。
- 修复：布局滚动区改为唯一的 `.app-main[role=main]`，页面根节点不再继承布局滚动样式，也不再出现嵌套 `main` 语义。
- 验证：浏览器读取修复前有 2 个 `overflow:auto` main（内层 `3471/3471`、外层 `3483/618`）；修复后仅 `.app-main` 可滚动（`3483/618`），页面 `<main>` 恢复为 `overflow:visible`。桌面与 390×844 视口真实滚轮向下均从 `scrollTop=0` 稳定到 `678`，等待后未回弹；向上滚轮恢复到 `0`。

## 批次 10：DASH-01～03 经营罗盘与异常下钻（v2.6.0）

- 范围：单店、分组、1/7/30 天和自定义日期；查询继续经过租户与账号范围校验。
- 指标：GMV、支付订单/买家、客单价、退款、曝光、访客、咨询、回复和动销商品均保留来源、覆盖度、样本量、同步时间与前周期对比。
- 分析：趋势支持订单、GMV、咨询和曝光切换；漏斗显式表达未同步上游；店铺/商品排行支持 Top/Bottom 和多指标排序。
- 异常：退款偏高、数据同步降级、高曝光低点击、高咨询低支付和低库存均返回证据、严重度、处理建议及安全站内路由；未同步点击或支付不能被当成 0 命中规则。
- 安全：商品、账号关联增加 `tenant_id` JOIN 条件；商品和订单下钻读取账号/商品筛选，不丢上下文。
- 权限：新增经营面板专属 `/api/business-analytics/scopes`，店铺和分组同时受租户与账号范围限制；只有面板权限的成员不再因账号管理接口返回 403。
- 登录：账号和密码保持第一、第二输入项；两步验证码默认隐藏，只有服务端确认需要 TOTP/恢复码后才显示为第三项。
- 主要文件：`BusinessAnalyticsService.java`、`BusinessAnalyticsController.java`、`BusinessAnalyticsServiceTest.java`、`AccessControlInterceptorTest.java`、`dashboard/index.vue`、`dashboard/useDashboard.ts`、`dashboard/dashboard.css`、`login/index.vue`、商品与订单页面。
- 数据迁移：无；复用 `xianyu_shop_metric_daily` 与 `xianyu_goods_metric_daily`，不伪造平台经营数据。
- 本机工具链：Data 分区启用 Temurin JDK 21.0.12.1、Node 22.23.2、npm 10.9.8，并将 Maven/npm 缓存保留在 `.tools/`；`scripts/local-toolchain.sh` 统一环境，`scripts/local-verify.sh` 执行完整原生回归。
- 测试：macOS 原生 Maven 160 项通过；经营与权限专项 17 项通过；前端类型检查通过，Vite 338 模块生产构建通过。
- 部署：3000 Java 应用由 macOS `launchd` 托管；MySQL 5.7 容器仅绑定 `127.0.0.1:13306` 并复用 `xianyusmart_matrix_handoff_mysql`，生产 2000 与生产数据卷不变。命令和边界见 `NATIVE_QA_RUNBOOK.md`。
- 隔离 API：3000 端口版本 `2.6.0`；最小面板权限账号读取 3 个可见店铺、2 个完整授权分组；2026-09-08～14 全店单日动销峰值 1518，GMV 19620，7 个趋势日；越权账号与超 366 天范围分别返回业务码 404/400。

## 缺陷批次：XYM-PUB-001～008 / PUB-01～03（v2.5.1）

- `XYM-PUB-001`：新增 `V40__repair_qa_channel_utf8.sql`，通过精确错误字节匹配只修复账号 102 的 `QA_LOCAL / MANUAL_IMPORT / QA_PARTIAL` 历史不可用原因；已有正确渠道名称、账号 103、真实平台渠道和生产商品均不修改。
- `XYM-PUB-002～003`：发布能力区补齐账号上下文、加载、错误重试和空状态；媒体上传改为逐项结果，失败文件保留并可重试/移除，跨步骤仍保存失败队列。
- `XYM-PUB-004`：商家任务增加任务 ID、请求 ID、账号精确筛选，查询上限扩展至 1000，并校验租户账号归属。
- `XYM-PUB-005～006`：新增 `V41__repair_qa_publish_outcome_evidence.sql` 回填隔离 QA 历史结果；本地待处理和结果未知均提供禁止真实通道重复发布的恢复提示，UNKNOWN 持久化 `QA_MOCK / QA_FIXTURE / platformNetworkCalls=false / platformWrite=NOT_PERFORMED`。
- `XYM-PUB-007`：结果页完整展示 requestId、taskId、itemId、通道、核验状态、来源、恢复建议和查询入口。
- `XYM-PUB-008`：发布执行及任务查询对跨租户/无归属账号返回 404，避免泄露资源存在性。
- 测试：中文失败原因和 QA UNKNOWN 安全证据单测通过；前端类型检查、生产构建、后端 155 项测试通过。MySQL 5.7 迁移和 API/界面回归在隔离 QA 3000 端口执行。
- 部署：仅更新隔离 QA `http://127.0.0.1:3000`；生产 2000 保持不变。

## 批次 8：XYM-ORD-001～003 售后幂等与 QA 权限（v2.4.1）

- `XYM-ORD-001`：命中 `requestId` 后逐项核对售后记录、方向、物流公司、运单号、状态、物流事件及毫秒精度时间；不同载荷返回 409。
- `XYM-ORD-002`：QA 售后控制面纳入订单菜单与 `action:order-write` 权限，并由服务层复核账号范围。
- `XYM-ORD-003`：并发唯一键竞争后以 `FOR UPDATE` 当前读跨越 MySQL 5.7 的旧快照，读取赢家并执行同一套载荷校验。
- `XYM-ORD-004`：仅买家退回方向更新售后主档进度与最新文案；卖家补发/换货只新增方向隔离的运单证据。
- 回归范围：完全一致重放、改运单号/方向/状态/事件/时间/售后 ID、并发赢家、SUPPORT 拒绝和有权角色允许。
- 部署：隔离 QA 按用户要求改为 `http://127.0.0.1:3000`；生产 2000 未改动。

## 缺陷批次：XYM-PRD-001 / PRD-02 P02-02、P02-11（2026-09-14）

- 修复 SKU 主档数量与子项明细不一致：API 输出声明数、已验证数、覆盖状态及可执行提示；前端区分真实无规格和未同步子项。
- SKU 分价转换为元，并读取平台状态、划线价、图片特征及逐 SKU 履约映射。
- 新增隔离 QA 4 SKU、50 SKU、无 SKU 对照夹具；生产迁移为“无”。
- 变更文件：`ProductMatrixService.java`、`ProductMatrixServiceTest.java`、`goods/index.vue`、`qa/fixtures/XYM-PRD-001-multi-sku.sql` 及版本/交付文档。
- 开发测试：定向后端 7 项通过；全量 117 项通过；前端类型检查与 337 模块生产构建通过。
- 隔离验证：MySQL 5.7 Flyway 35/35；`QA-GOODS-0864` 4/4、`QA-GOODS-0960` 50/50、`QA-GOODS-0000` 0/0 且为 `EMPTY_VERIFIED`；Tenant-B 泄漏记录 0。
- Product Design QA：Chrome 桌面和 390×844 检查 4/50 SKU 抽屉；一致性提示、单列重排、独立滚动及固定动作区可见，无核心字段裁切。
- 部署：隔离 QA `http://127.0.0.1:12401` 使用 `xianyusmart:2.3.1`，镜像摘要 `sha256:7aa1ee4b2228563577d0f47b6085889b790423fa14fc3ebd021a21d4fa138dfa`；生产 2000 未改动。

## 批次 0：现状盘点与基线（2026-09-13）

### 需求范围

- 全量需求基线与鱼麦多四份只读评审证据。
- 为第一批 `ACC → PRD → PUB → ORD → IM/AUD` 实施建立代码与测试基线。

### 已有实现

- 租户隔离：MyBatis 租户拦截器、服务层 `tenant_id` 条件和账号范围上下文。
- 店铺范围：`ALL/SELECTED`、`sys_user_account_scope`、SQL 数据权限拦截器。
- 基础权限：16 个菜单权限、10 个宽粒度动作权限。
- 可靠性：发布请求幂等键、履约租约/重试/人工复核、通知 outbox、运营异常队列。
- 账号连接：扫码/Cookie、连接启停、二维码续期、验证码处置、运行档案和能力探测。
- 前端工作树：商品详情 360 骨架、商品多选/批量动作入口、双收件箱、全局响应式视觉改造；均保留，不回退。

### 核心缺口

- `ACC-01/02/04/05`：缺接入通道档案、店铺经营画像快照、处罚生命周期和账号资产健康 API。
- `PRD-01/03/04`：缺平台真值/本地缓存分层、统一商品事件和后端批量任务。
- `PUB-01/03`：缺通道能力快照、平台成功/本地待修复/超时未知统一结果。
- `ORD-01/02/04`：缺退款决策模型、实物物流和完整订单事件。
- `AUD-01`：日志查询仍强制单账号，缺请求 ID、结果分层、关键词/操作者/时间筛选和导出审计。
- 权限：缺账号删除、凭证维护、商品删除、批量改价、退款同意/拒绝、卡密导出、审计导出、成员权限调整等独立权限。

### 变更文件

- 新增本实施记录。

### 数据迁移

- 无。

### 测试

- `JAVA_HOME=/Volumes/Data/codex/xianyu/.tools/jdk21/Contents/Home ./mvnw test`
- 结果：67 项通过，0 失败，0 错误，0 跳过。

## 批次 1A：账号资产、画像、处罚与连接风险

状态：后端基础完成，前端待统一 Product Design 阶段接入。

### 需求范围

- `ACC-01`、`ACC-02`、`ACC-04`、`ACC-05`、`ACC-07`。
- `AUD-01`、`NFR-01`、`NFR-02` 的账号域基础。

### 已完成

- 新增接入通道档案和能力矩阵，保存连接/授权/到期/来源/覆盖度/最近成功及错误；凭据本身不进入该表和接口。
- 新增账号数据集同步状态，区分 `UNSYNCED/PARTIAL/FULL`，保证未同步画像、处罚和统计不显示为 0。
- 新增店铺画像不可变快照，数值字段允许为空，保留来源、请求 ID、同步状态、覆盖度、错误和原始快照。
- 新增处罚事件与本地处理历史，平台处罚状态与本地处理状态分离；处理请求使用请求 ID 幂等，不调用平台申诉。
- 新增账号矩阵汇总、列表、360 详情、处罚查询/导出、画像写入、处罚同步及接入通道状态 API。
- 审计日志增加请求 ID、幂等键、本地/平台结果层、数据来源、平台响应码和字段差异。
- 审计查询支持模块、操作者、店铺、状态、结果层、时间、请求 ID 与关键词；CSV 导出受独立权限控制且导出自身记审计。
- 增加 12 个细粒度敏感动作权限；账号删除、凭据维护、账号批量、风险处理/导出、商品删除/批量改价、退款同意/拒绝、卡密导出、审计导出及成员权限调整不再共用宽权限。
- 新表同时接入 MyBatis 租户拦截和账号范围 SQL 拦截；所有 `JdbcTemplate` 查询显式携带 `tenant_id`，单账号入口同时校验账号授权范围。

### 变更文件

- `src/main/resources/db/migration/V26__account_asset_profile_risk_audit.sql`
- `src/main/java/com/xianyusmart/controller/AccountMatrixController.java`
- `src/main/java/com/xianyusmart/service/AccountMatrixService.java`
- `src/main/java/com/xianyusmart/entity/XianyuOperationLog.java`
- `src/main/java/com/xianyusmart/controller/OperationLogController.java`
- `src/main/java/com/xianyusmart/service/OperationLogService.java`
- `src/main/java/com/xianyusmart/service/impl/OperationLogServiceImpl.java`
- `src/main/java/com/xianyusmart/mapper/XianyuOperationLogMapper.java`
- `src/main/java/com/xianyusmart/service/PermissionCatalog.java`
- `src/main/java/com/xianyusmart/interceptor/AccessControlInterceptor.java`
- `src/main/java/com/xianyusmart/config/MybatisPlusConfig.java`
- `src/main/java/com/xianyusmart/config/AccountDataPermissionHandler.java`
- `src/test/java/com/xianyusmart/service/AccountMatrixServiceTest.java`
- `src/test/java/com/xianyusmart/service/OperationLogServiceImplTest.java`
- `src/test/java/com/xianyusmart/service/PermissionCatalogTest.java`
- `src/test/java/com/xianyusmart/interceptor/AccessControlInterceptorTest.java`
- `src/test/java/com/xianyusmart/config/AccountDataPermissionHandlerTest.java`

### 数据迁移

- `V26`：4 张账号资产表、1 张数据集同步状态表、审计字段/索引及新增权限回填。
- 空库验证：临时 `mysql:5.7.18` 从 `V1` 连续执行至 `V26` 成功，应用在隔离端口启动，`/actuator/health` 返回 `UP`；临时应用与数据库容器均已关闭并删除。

### 测试

- 定向：`./mvnw -Dtest=AccountMatrixServiceTest,AccountDataPermissionHandlerTest,PermissionCatalogTest,OperationLogServiceImplTest,AccessControlInterceptorTest test`
- 结果：18 项通过，0 失败，0 错误，0 跳过。
- 全量：`JAVA_HOME=/Volumes/Data/codex/xianyu/.tools/jdk21/Contents/Home ./mvnw test`
- 结果：83 项通过，0 失败，0 错误，0 跳过。
- 安全边界：未连接生产店铺，未执行发布、退款、删除、发货或申诉验证。

## 批次 1B：商品真值、批量任务与发布补偿

### 需求范围

- `PRD-01/02/03/04`、`PUB-01/02/03`、`NFR-02/03`。

### 已完成

- 商品主档增加来源、发布通道、平台/本地同步状态、覆盖度、库存、类目和经营模式；商品详情返回 SKU、营销、1/7/30 天指标和事件时间线。
- 商品事件与日指标表分离平台事实和本地状态；未同步营销与指标保持 `null/UNSYNCED`。
- 批量上架、下架、改价、库存、擦亮、删除、同步统一进入父子任务；预检显示店铺数、商品数、冲突和精确确认文案。
- 按店串行/限速、逐件结果、失败/未知重试及失败 CSV；修复筛选快照原先只能取前100条的问题，单任务最多500条。
- 发布能力矩阵、最终请求同源预览、dry-run、请求幂等、超时未知禁止盲重试、平台成功本地待修复与商品事件闭环。
- P2：用户级保存筛选器、跨页筛选快照、跨店擦亮队列、商品经营 CSV（最多10000条）。

### 主要变更

- 迁移：`V27__product_truth_events_batch.sql`、`V28__publish_outcome_and_recovery.sql`、`V34__saved_product_filters.sql`。
- 服务/API：`ProductMatrixService/Controller`、`ProductBatchExecutionService`、`ProductSyncStateService`、`ProductEventService`、`PublishCapabilityService`、`PublishingController`、`PlatformPublishService`。

## 批次 1C：订单、退款、客服、通知与审计

### 需求范围

- `ORD-01/02/03/04/05`、`IM-01/02/03`、`CRM-01`、`NTF-01/02/03`、`AUD-01`。

### 已完成

- 跨店订单与退款检索、概览、详情、物流/退款/评价/消息/操作时间线；金额不可确认时保持空值。
- 虚拟履约沿用原有原子库存和防重复；实物运单只允许记录“已在平台人工确认”的事实；无可靠退款 API 时同意/拒绝明确不可用。
- 订单辅助动作按订单状态返回动态能力；订单备注/旗帜可用，订单改价和小法庭写操作不伪造。
- 双收件箱服务端未读、通知已读/处理分离、会话历史覆盖度、人工接管、文本/图片发送 ACK 与 `UNKNOWN` 防重复语义。
- 会话置顶、关键词标记、客户备注和黑名单；黑名单同步到买家自动化拦截。
- 买家档案租户归属修正；金额改用 nullable `order_amount` 并返回可信金额样本数，不再把未知金额汇总为0。
- 通知先写站内事件再进入外部 outbox；渠道支持 ALL/GROUPS/ACCOUNTS 和事件订阅；配置写操作进入审计。

### 主要变更

- 迁移：`V29__order_refund_logistics_timeline.sql`、`V30__conversation_inbox_and_notification_events.sql`、`V31__groups_analytics_and_scope.sql`。
- 服务/API：`OrderMatrixService/Controller`、`MessageWorkspaceService/Controller`、`NotificationInboxService`、`NotificationCenterService/Controller`、`BuyerProfileService`。

## 批次 2：经营分析、分组、团队与安全

### 需求范围

- `DASH-01/02/03`、`ACC-03/06`、`SEC-01/02`、`AUTO-03`。

### 已完成

- 店铺分组增删改、成员替换、账号矩阵筛选；删除分组不删除店铺。
- 分组可用于成员动态授权，后端请求开始时展开为账号集合，并继续由 SQL 数据权限拦截。
- 经营日聚合、当前/前周期、趋势、漏斗、店铺/商品排行和异常规则；所有输出携带来源、覆盖度、样本量与同步时间。
- 曝光、访客、粉丝等未同步平台指标保持空值并返回专属说明；不从本地订单/消息反推。
- 2FA 密钥 AES-GCM 加密、恢复码、设备列表、单设备撤销和踢出其他设备。
- access token 默认30分钟，refresh token 默认30天并强制轮换；重复使用旧 refresh token 会撤销设备会话。
- 团队角色、菜单/动作权限、账号/分组范围与配置审计闭环；现有任务租约、重试、下一步、失败原因和人工处理继续承担 `AUTO-03`。
- 私有化数据布局启用 InnoDB file-per-table，数据库表空间按真实数据增长，不集中为预分配的单个20GB业务文件。

### 主要变更

- 迁移：`V31__groups_analytics_and_scope.sql`、`V32__refresh_token_rotation.sql`、`V33__member_group_scope.sql`。
- 服务/API：`AccountGroupService/Controller`、`BusinessAnalyticsService/Controller`、`AccountAccessService`、`PlatformUserService`、`SecurityController`、`TotpService`、`AuthServiceImpl`。
- 部署：`compose.yaml`、`.env.example`、`application.yaml`。

### 后端门禁

- `JAVA_HOME=/Volumes/Data/codex/xianyu/.tools/jdk21/Contents/Home ./mvnw test`
- 结果：108 项通过，0 失败，0 错误，0 跳过。
- `V1` 至 `V34` 已在全新 `mysql:5.7.18` 空库连续迁移，34 个迁移全部成功；隔离应用健康启动并完成管理员初始化。
- 安全边界：未对生产店铺执行发布、退款、删除、发货、申诉或平台改价验证。

## 批次 3：Product Design 全站工作台与响应式验收

### 需求范围

- `DASH-01/02/03`、`ACC-02/03/04/05/06/07`、`PRD-01/02/03/04`、`PUB-01/02/03`、`ORD-01/02/03/04/05`、`IM-01/02/03`、`NTF-01/02/03`、`SEC-01` 的前端闭环。
- `NFR-01/02/03` 的来源、覆盖度、响应式、幂等请求和不可确认结果表达。

### Product Design 证据与决策

- 先以截图审查鱼麦多商品列表/详情抽屉、账号矩阵，再审查本地旧版概览、账号、商品、发布、消息和通知页；只借鉴矩阵信息架构、详情不丢上下文、范围明确、来源可见和安全确认，不复制品牌、素材或文案。
- 将经营概览、账号、商品和订单统一为“范围/筛选 → 来源与覆盖 → 列表 → 360° 抽屉”的工作台结构；未知金额、曝光、退款、画像和风险保持 `—/未同步`。
- 商品批量操作在未选择商品时阻止执行，并提示使用当前筛选快照；后端仍要求预检、精确确认文案、限速和请求 ID。
- 通知渠道增加 ALL/GROUPS/ACCOUNTS 范围配置，非全量范围不得为空；成员无权访问的账号由服务端再次过滤。
- 消息工作台接入服务端未读、人工接管、ACK/UNKNOWN、置顶/标记/备注/黑名单；空账号时显示明确状态。
- 全局引入统一工作台基础样式，避免直达按需加载路由时缺失间距/响应式样式。

### 主要前端变更

- API/登录：`vue-code/src/utils/request.ts`、`vue-code/src/api/auth.ts`、`vue-code/src/api/matrix.ts`、`vue-code/src/api/message.ts`、`vue-code/src/api/operations-health.ts`、`vue-code/src/api/admin-user.ts`、`vue-code/src/views/login/index.vue`。
- 概览：`vue-code/src/views/dashboard/index.vue`、`useDashboard.ts`、`dashboard.css`。
- 账号：`vue-code/src/views/accounts/index.vue`、`accounts.css`。
- 商品与发布：`vue-code/src/views/goods/index.vue`、`goods.css`、`vue-code/src/views/product-publish/index.vue`。
- 订单：`vue-code/src/views/orders/index.vue`、`orders.css`。
- 客服/通知/权限：`vue-code/src/views/messages/workspace.vue`、`vue-code/src/views/operations-health/index.vue`、`vue-code/src/views/admin-users/index.vue`。
- 全局视觉：`vue-code/src/assets/main.css`、`theme.css`、`vue-code/src/styles/merchant-workbench.css`、布局和导航组件。
- 生产静态资源：`src/main/resources/static/`。

### 数据迁移与隔离运行

- 全新临时 MySQL 5.7 数据库从 `V1` 连续执行至 `V34`，验证 34 个迁移后处于最新版本。
- 最新 JAR 在隔离空库和独立 12401 端口启动；前端 QA 使用独立 4174 端口代理，不读取正式数据库、不包含真实闲鱼账号，现有 2000 端口服务未被替换。
- 初始化弱口令按安全策略被拒绝；隔离 QA 改用临时强口令，未降低正式环境安全规则。

### 测试与视觉门禁

- 前端：`docker run --rm -v /Volumes/Data/codex/xianyu/vue-code:/workspace -v xianyusmart-vite-node-modules:/workspace/node_modules -w /workspace node:22-alpine npm run build`
- 结果：Vue TypeScript 类型检查通过，Vite 330 个模块生产构建成功。
- 后端：`JAVA_HOME=/Volumes/Data/codex/xianyu/.tools/jdk21/Contents/Home ./mvnw test`
- 结果：108 项通过，0 失败，0 错误，0 跳过。
- 整包：`JAVA_HOME=/Volumes/Data/codex/xianyu/.tools/jdk21/Contents/Home ./mvnw -DskipTests package`
- 结果：`target/xianyusmart-2.1.0.jar` 构建成功，包含最终静态资源。
- 浏览器：Chrome 实测 1920×1080、1366×768、390×844、3840×2160；覆盖概览、账号、商品、订单、消息、商品发布、通知渠道、站内收件箱、团队权限和关键弹窗；控制台错误为 0。
- 安全边界：只使用隔离空库和虚构表单内容；未提交发布预检/执行，未执行真实发货、退款、删除、申诉、改价或外部通知。

## 批次 4：独立 QA、缺陷关闭与发布门禁

### 需求范围

- 全量回归 `ACC/PRD/PUB/ORD/IM/CRM/NTF/DASH/SEC/AUD/AUTO/NFR`。
- 发布版本 `v2.2.0`。

### 缺陷与修复

- `P0`：MySQL 5.7 不允许会话聚合查询在当前 `INSERT ... SELECT` 形态中以输出别名执行 `HAVING first_message_time`；改为重复聚合表达式，并新增 SQL 形态回归测试。隔离空库消息会话恢复 HTTP/API 200。
- `P1`：账号分组和商品弹窗缺少完整键盘语义；新增共享 `useModalFocusTrap`，实现打开聚焦、背景 `inert/aria-hidden`、Tab/Shift+Tab 首尾循环、Esc 关闭和焦点返回。批量弹窗复用同一机制。
- `P2`：390px 宽度下账号/商品/订单概览卡和发布步骤需要横向滑动；改为双列网格，4 个发布步骤为 2×2，页面无全局横向溢出。
- 数据边界：空账号范围不再把风险覆盖判断为 `FULL`，而是返回 `UNSYNCED` 和未知风险数 `null`；经营概览未同步证据显示 `—`。

### 变更文件

- 后端：`ConversationAssignmentService.java`、`AccountMatrixService.java`。
- 后端测试：`ConversationAssignmentServiceTest.java`、`AccountMatrixServiceTest.java`。
- 前端：`vue-code/src/composables/useModalFocusTrap.ts`、账号/商品/订单/发布/概览页面与工作台样式。
- 版本与发布：`pom.xml`、前端 package 元数据、`application.yaml`、`Dockerfile`、`compose.yaml`、`README.md`、`CHANGELOG.md`。

### 迁移

- 无新增迁移；最终数据库版本仍为 `V34`。

### 测试命令与结果

- `JAVA_HOME=/Volumes/Data/codex/xianyu/.tools/jdk21/Contents/Home ./mvnw test`
- 结果：110 项通过，0 失败，0 错误，0 跳过。
- `docker run --rm -v /Volumes/Data/codex/xianyu/vue-code:/workspace -v xianyusmart-vite-node-modules:/workspace/node_modules -w /workspace node:22-alpine npm run build`
- 结果：Vue TypeScript 类型检查通过，Vite 334 个模块生产构建成功。
- 独立 QA：消息会话、空范围覆盖、弹窗焦点、390×844 响应式、刷新令牌轮换、未授权访问与控制台均通过；无新增警告或错误。
- 安全边界：未执行生产发布、发货、退款、删除、申诉、平台改价或外部通知；隔离空库无法黑盒覆盖真实实体详情和平台回执，相关状态机由服务测试覆盖。

### 本地发布与运行校验

- 发布前备份：`/private/tmp/xianyusmart-predeploy-20260914/xianyusmart.sql`，SHA-256 `3f6e14af264592590a8249317df407e52c0be74e26af573d9a35c3d2df2d5b1d`；备份位于本机临时目录，不纳入 Git。
- 镜像：`docker build -t xianyusmart:2.2.0 .` 成功；镜像标签与 OCI 版本均为 `2.2.0`。
- 部署：仅使用 `docker compose up -d --no-deps --force-recreate --no-build app` 重建应用容器；MySQL、Nginx 与数据卷保持原位。
- 迁移：正式本地库从 `V25` 顺序执行 `V26` 至 `V34`，Flyway 校验 34 个迁移并成功到达 `V34`。
- 运行：`xianyusmart-app-1` 使用 `xianyusmart:2.2.0` 且状态为 `healthy`；`https://127.0.0.1:2000/actuator/health` 返回 `UP`，`/api/system/version` 返回 `2.2.0`。
- 边界：发布后只执行只读健康和版本检查；未对生产店铺执行发布、退款、删除、发货、申诉、改价或通知发送验证。

## 批次 5：独立测试交接门禁与 `QA-001`

### 缺陷与修复

- `QA-001/P0`：大数据量夹具首次覆盖到经营排行 SQL；MySQL 5.7 在 `ONLY_FULL_GROUP_BY` 模式下将 `ORDER BY gmv` 解析为未分组的原始列，导致经营概览失败。
- 店铺排行改为 `ORDER BY SUM(metric.gmv)`，商品排行改为 `ORDER BY SUM(metric.paid_amount)`，不再依赖可能与原始列冲突的聚合别名。
- `BusinessAnalyticsServiceTest` 新增 MySQL 5.7 聚合排序形态回归断言。

### 独立 QA 环境

- QA 前端：`http://127.0.0.1:12401/`；QA API：`http://127.0.0.1:12401/api`。
- 使用全新独立 MySQL、应用数据和日志卷，不挂接正式数据库；关闭 AI 外部调用且无真实闲鱼凭据。
- Tenant-A 提供 OWNER、TENANT_ADMIN、OPERATOR、SUPPORT、FINANCE、禁用成员；Tenant-B 提供独立 OWNER。
- 安全对象：Tenant-A 店铺 `101/102/103`、Tenant-B 店铺 `201`；覆盖完整、部分、未同步、失败、UNKNOWN、SELECTED 范围、XSS 文本与跨租户 ID 猜测。
- 大数据量：Tenant-A 包含 1,000 商品、300 订单、400 消息、30 通知、90 个店铺日指标样本。
- 版本提升为 `v2.2.1`；迁移版本仍为 `V34`，无新增数据库结构迁移。

### 最终测试与 Product Design QA

- `BusinessAnalyticsServiceTest` 定向回归：2 项通过，0 失败。
- `JAVA_HOME=/Volumes/Data/codex/xianyu/.tools/jdk21/Contents/Home ./mvnw test`：111 项通过，0 失败，0 错误，0 跳过。
- `docker run --rm -v /Volumes/Data/codex/xianyu/vue-code:/workspace -v xianyusmart-vite-node-modules:/workspace/node_modules -w /workspace node:22-alpine npm run build`：Vue TypeScript 类型检查通过，Vite 334 个模块生产构建成功。
- `docker build -t xianyusmart:2.2.1 .`：镜像内再次完成前端类型检查、334 模块生产构建及后端 111 项测试，镜像构建成功。
- MySQL 5.7 全新库迁移验证保持 `V1` 至 `V34` 全部成功；本批无结构迁移。
- Chrome 覆盖桌面 1920×1080、1366×768、4K 与 390×844 窄屏；验证空状态、加载状态、持久错误与重试、无权限 API/导航、大数据量和失败/UNKNOWN 数据表达，无页面级横向溢出或控制台错误。
- Product Design 故障注入发现商品表慢请求时缺少可见反馈；新增保留上下文的加载条、持久错误说明和“重新加载”入口，数据库恢复后原筛选与 1,000 条结果可正常恢复。

### 最终部署

- 正式本地服务与隔离 QA 均使用 `xianyusmart:2.2.1`；应用、QA 应用和两套 MySQL 均健康。
- 正式地址 `https://127.0.0.1:2000/`，隔离 QA 地址 `http://127.0.0.1:12401/`；两个版本接口均返回 `2.2.1`。
- 只对隔离 QA 容器执行慢请求、数据库中断与恢复注入；正式本地服务仅进行只读健康和版本检查。

## 批次 6：PRD-01～04 商品模块硬验收收口

### 需求范围

- `PRD_01_04_ACCEPTANCE.md` 的 P01-01～14、P02-01～14、P03-01～14、P04-01～22。
- 本批只修改商品矩阵、商品详情、单品能力和持久化商品任务中心；生产商品不参与破坏性测试。

### 已完成

- 商品列表改为服务端生命周期聚合、组合筛选、分页、来源/覆盖/窗口口径和行级经营指标；未同步保持空值。
- 详情五页签接入真实详情模型，增加订单摘要、本地资料编辑、焦点陷阱和平台能力安全降级。
- 增加跨页筛选快照、排除项、预检、父子任务、幂等、row_version、按店限速、退避、部分成功、UNKNOWN、重启恢复、安全取消、失败重试、导出、通知、事件和审计。
- 任务执行前再次复核用户状态、动作权限和店铺范围；受限用户的任务汇总按可见子项重新计算，避免数量和错误泄漏。
- 新增完整任务中心的动作/店铺/操作者/状态/时间/任务 ID 筛选、逐件证据和响应式界面。

### 变更文件与迁移

- 后端：`ProductMatrixController.java`、`ProductMatrixService.java`、`ProductBatchExecutionService.java`、`NotificationCenterService.java`、`AccessControlInterceptor.java`。
- 前端：`vue-code/src/views/goods/index.vue`、`vue-code/src/views/product-tasks/index.vue`、`vue-code/src/api/matrix.ts`、路由/导航/请求层及生成静态资源。
- 测试：`ProductMatrixServiceTest.java`、`ProductBatchExecutionServiceTest.java`。
- 迁移：`V35__product_batch_state_machine.sql`。
- 逐项报告：`PRD_01_04_IMPLEMENTATION_REPORT.md`。

### 测试与 Product Design QA

- Docker 内 `npm run type-check && npm run build:spring`：通过，Vite 337 模块。
- Docker 内 `./mvnw clean package`：114 项通过，0 失败/错误/跳过。
- `docker build -t xianyusmart:2.3.0 .`：成功，摘要 `sha256:a2b6181262f0a4d322ccf45a4c401dfa6732aaa848b3575297fc1442861fc1b4`。
- 隔离 QA：`http://127.0.0.1:12401/`，MySQL 5.7 Flyway 35/35 校验，应用 healthy。
- Chrome：桌面、390×844、缩放、1000 商品、五页签、未同步语义、跨页预检、部分成功/失败/UNKNOWN、CSV 导出和键盘焦点完成开发侧设计 QA。
- 安全边界：未创建真实平台写任务；未执行生产商品上下架、改价、库存、擦亮或删除。

## 批次 7：ORD-04/05 退货、换货与售后物流证据

### 需求范围

- 订单详情中的退款/售后案例、关键处理期限、平台下一步与双向售后运单。
- 只记录已在闲鱼平台确认的真实事实；没有可靠平台适配器时不提供退款、退货或换货的假执行入口。

### 已完成

- `xianyu_refund_case` 增加售后类型、退货状态、卖家/买家截止时间、平台下一步与最新状态说明。
- 新增租户和店铺隔离的 `xianyu_return_shipment`，覆盖买家退回与卖家补发/换货方向。
- 新增售后运单预检和记录 API，执行完整确认文案、人工平台证据、幂等和重复运单保护，并写入订单时间线与统一审计。
- 退款详情展示来源、覆盖状态、期限、平台动作、运单历史与处理记录；案例未同步时不再显示成 0。
- 新增仅限 QA profile、白名单租户/店铺、`QA-ORDER-` 前缀的可回放售后夹具，外部平台网络调用为 0。

### 变更文件与迁移

- 迁移：`V39__after_sales_return_evidence.sql`。
- 后端：`OrderMatrixService.java`、`OrderMatrixController.java`、`QaOrderAfterSalesController.java`、租户与账号隔离配置。
- 前端：`vue-code/src/views/orders/index.vue`、`orders.css`、`vue-code/src/api/matrix.ts` 及最终生产静态资源。
- 测试：`OrderMatrixServiceTest.java`；隔离 E2E 方式见 `ORD_04_05_QA_E2E_GUIDE.md`。

### 安全边界

- 不调用闲鱼退款、退货、换货、补发平台写接口；所有保存响应明确 `platformWrite=NOT_PERFORMED`。
- 不使用生产店铺做破坏性验证；开发与独立测试仅使用 Tenant-A 的 QA 白名单订单。

## 批次 9：PUB-01～03 发布真实性与隔离验收通道（v2.5.0）

### 本批基线

- 新增 `PUB_01_03_ACCEPTANCE.md`，细化多通道、发布表单/预检、发布结果/补偿的 30 项硬验收。
- 明确叶子类目、原价、成色、outerId、完整类目属性、视频与多 SKU 的现存平台适配缺口；无证据能力继续安全降级。

### 已完成的后端收口

- 发布通道能力改为读取 `capabilities_json` 证据；没有 SKU 探测证据时返回 `NOT_VERIFIED`，不再默认 READY。
- 前后端发布接口权限统一到商品菜单和商品写权限。
- 发布请求新增业务载荷 SHA-256 指纹；同请求同载荷复用任务，不同载荷返回 409，并处理数据库唯一键并发赢家。
- 新增仅限 QA profile、显式开关、Tenant-A、账号 101/102/103、`QA_LOCAL` 和 `QA-PUBLISH-` 标题前缀的隔离发布适配器。
- QA SUCCESS/LOCAL_PENDING/UNKNOWN 均明确 `QA_FIXTURE/QA_MOCK`，平台网络与平台写调用为 0；UNKNOWN 最大尝试次数为 1。
- 发布任务的统一审计开始写入请求 ID、幂等键、结果层和数据来源；商品事件区分真实平台与 QA 夹具。

### 验证结果

- 最终 Docker 构建：前端类型检查和生产构建通过，338 个模块；Maven 153 项测试通过，0 失败、0 错误、0 跳过。
- 镜像：`xianyusmart:2.5.0`，manifest `sha256:77b846c397a5383c322a9f8a61f78262177132495ecf2a581021e87e3562a1ca`。
- 3000 QA 健康状态 `UP`，版本接口返回 `2.5.0`；生产端口 2000 未变更。
- 价格 `1.234` 返回 400；QA SUCCESS 返回 `QA_CONFIRMED/QA_FIXTURE`，同载荷重放复用任务，不同载荷同 requestId 返回 409。
- QA UNKNOWN 落为 status=4、verification=UNKNOWN、data_source=QA_FIXTURE、attempt=1/max=1，并可按 requestId 查询。
- 操作审计记录 requestId、idempotencyKey、`LOCAL_SUCCESS/UNKNOWN` 与 `QA_FIXTURE`；商品事件记录 `PUBLISH/QA_CONFIRMED/QA_FIXTURE`。
- Product Design QA 覆盖桌面四步流、390×844 窄屏、预检证据、二次确认、无可用通道空/错态和浏览器错误日志；修复时间本地化、授权状态文案及无通道仍可继续的问题。

### 迁移与降级

- 本批无数据库结构迁移；最新迁移仍为 `V39`，QA MySQL 5.7.18 继续复用既有 schema。
- 叶子类目/行业、原价、成色、outerId、完整类目属性、视频与多 SKU 仍为显式安全降级，不宣称已具备真实平台写能力。

## 批次 10：全功能浏览器盘点、V3 基线与发布草稿工作台（开发中）

### 本批需求与证据

- 逐路由点击审查现有系统，并打开账号360、连接凭证/续期、商品/任务详情、订单退款/售后、通知、权限、系统设置等主要二三级入口。
- 桌面与 390×844 的页面证据、现状判断和缺口编号记录在 `XIANYU_MATRIX_FULL_UI_AUDIT_2026-09-14.md`。
- 新增长期产品合同 `XIANYU_MATRIX_V3_PRODUCT_REQUIREMENTS.md`，以及 Wave 0～5 实施顺序 `XIANYU_MATRIX_LONG_TERM_EXECUTION_PLAN.md`；历史 PRD/PUB/ORD 等验收编号继续保留。
- 本批仍处于 Wave 0/Wave 1 开发，不宣称全系统功能、Product Design 或独立测试已经完成。

### 已完成的当前增量

- `PUB-02/04/05/06/09`：商品发布工作台扩展为店铺通道、类目属性、内容媒体、价格库存、多 SKU、交易服务、预检发布和买家预览共用的一份结构化草稿。
- `PUB-05/07/08`：新增 V42 发布草稿/版本/预检基础模型、草稿服务与接口；真实平台高级字段缺能力时仍安全降级。
- `ACC-06/07`：扫码续期弹窗展示闲鱼账号 ID、系统店铺 ID、账号备注和后端有效期倒计时；过期/失败可重新生成，本地窗口最长 15 分钟并提示平台可能提前失效。
- `BASE-02/ACC-03`：共享布局增加路由加载提示；连接页先展示账号、再并行读取各店连接状态，手机首屏增加明确加载状态，不把读取中当作空数据。

### 变更文件与迁移

- 需求：`XIANYU_MATRIX_FULL_UI_AUDIT_2026-09-14.md`、`XIANYU_MATRIX_V3_PRODUCT_REQUIREMENTS.md`、`XIANYU_MATRIX_LONG_TERM_EXECUTION_PLAN.md`。
- 后端：`ListingDraftService.java`、`PublishingController.java`；迁移 `V42__listing_draft_workbench.sql`。
- 前端：发布工作台、`ListingSkuEditor.vue`、`ListingPhonePreview.vue`、`matrix.ts`；连接页、二维码续期弹窗、共享布局和最终生产静态资源。
- 测试：`ListingDraftServiceTest.java`。

### 当前验证结果

- `scripts/local-toolchain.sh npm --prefix vue-code run type-check`：通过。
- `scripts/local-toolchain.sh npm --prefix vue-code run build-only`：通过，Vite 348 个模块。
- `scripts/local-toolchain.sh ./mvnw -Dtest=ListingDraftServiceTest test`：5 项通过，0 失败、0 错误。
- `scripts/local-toolchain.sh ./mvnw test`：165 项通过，0 失败、0 错误、0 跳过。
- `scripts/native-qa.sh deploy`：JAR 构建成功，macOS 裸机应用在 `127.0.0.1:3000` 启动，MySQL 5.7 Docker 在 `127.0.0.1:13306`；Flyway 校验 42/42，schema 当前版本 V42。
- 浏览器只读/安全验收：发布六段表单与预览真实渲染；续期二维码成功生成，账号 ID `qa-tenant-a-full`、系统店铺 ID `101`、备注和 `15:00` 倒计时同屏；未扫码确认。
- 隔离发布 E2E：草稿保存并重新载入成功，预检 `PREFLIGHT_PASSED`；任务 19 返回 `QA_CONFIRMED`、`QA_FIXTURE`、`NOT_PERFORMED`；同 requestId 重放仍返回任务 19，并明确没有重复创建任务。期间修复草稿 ID 字符串/数字比较和幂等回读数据来源不一致。
- 已知环境噪声：隔离 QA 的版本检查服务返回 HTTP 403，只影响“检查更新”，不影响应用健康、迁移和本批功能；后续应改为可诊断的非错误级降级。

### 安全边界与待办

- 未执行生产商品发布、改价、上下架、删除、发货、退款、申诉、通知发送或权限保存。
- MySQL 5.7 升级库已验证至 V42；V1～V42 空库迁移随后也已完成。手机 390px 全路由最终复测、错误/无权限/大数据状态和独立测试交接仍待完成，不能以当前单元测试和单条 QA E2E 替代。

### Product Design 当前实现审查（2026-09-15）

- 使用最终源码重新生产构建后，在桌面与 390×844 窄屏重新截图检查商品发布、连接列表/详情、续期二维码、账号矩阵与账号 360；证据和判断记录在 `XIANYU_MATRIX_PRODUCT_DESIGN_AUDIT_2026-09-15.md`。
- 商品预览的失效图片改为可恢复错误态；桌面连接详情和列表改为成功/警告/危险/中性四级状态；手机连接详情同步状态语义、修复字符串 `"0"` 待恢复数量误判，并将窄屏状态卡改为单列避免动作按钮裁切。
- 手机商品发布可从第 6 步定位到底部，等待平滑滚动结束后位置正确；滚轮上滑后位置保持，没有复现自动回顶。仍将全局长页滚动列为 Wave 0 回归项，不以单页一次验证关闭用户报告。
- 本轮最终 `scripts/native-qa.sh deploy` 再次通过前端类型检查、Vite 351 模块生产构建和 Maven 打包；原生 3000 服务健康。完整错误/无权限/大数据/200%/4K 和全路由设计门禁尚未完成，因此没有向独立测试声明完成。
- 全新 MySQL 5.7 验证：在隔离容器中新建 `xianyusmart_fresh_v42_20260915` 空库，macOS 裸机 Java 临时运行于 13001；Flyway 从空 schema 顺序执行 V1～V42，用时 4.619 秒，`flyway_schema_history` 返回 42 条、最大版本 42、成功 42，健康端点为 `UP`。验证进程已正常停止，原生 3000 QA 服务未中断。
- Wave 0 手机 P0 回归：商品任务、自动发货、自动回复冷路由均先出现共享加载状态，随后展示真实内容；发货/回复均能从商品列表进入二级配置页面。新增共享 `GoodsThumbnail.vue`，失效或空商品图片显示可访问的中性占位，避免破图图标和 alt 文本挤压商品标题。

### DASH-01～03 独立测试缺口修复（进行中）

- 修复范围筛选不落 URL：单店与分组选择分别写入 `accountId/groupId`，互斥且保留其他查询参数；浏览器验证选择店铺 101 后刷新仍保持 `accountId=101` 和选中值 101。
- 退款金额/退款率、咨询买家/回复率拆成独立指标卡；店铺排行补咨询维度，商品排行补点击/咨询/收藏，并在商品行展示数据来源。
- 范围接口失败不再静默忽略，页面保留错误说明与“重试范围”；趋势完整覆盖改为同时要求所有请求店铺均有记录且每条来源均为 FULL。
- 新增仅 `qa` profile 注册的 `QaBusinessAnalyticsController`：复用租户、店铺和 `QA-` 商品前缀白名单，要求经营看板菜单与系统写权限，只写本地 `QA_FIXTURE/FULL` 商品日指标，平台网络调用为 0。
- 夹具支持 1～100 条、请求幂等和统一审计；实际写入店铺 101 的 100 条商品指标后，经营接口返回 100 条商品排行、7 条异常，覆盖高曝光低点击、高咨询零支付与低库存。同 requestId 重放后审计总数保持 1。
- Product Design 验证覆盖 1920×1080 与 390×844；窄屏截图发现趋势/漏斗 Grid 被图表固有宽度撑开，改为可收缩轨道后页面、列和卡片 `scrollWidth===clientWidth`。
- 将既有 `designqa_admin` 收紧为仅 `menu:dashboard`、`SELECTED` 店铺 101；随机密码只保存在本机钥匙串服务 `xianyusmart-native-qa-designqa`。实际验证 scopes 只返回店铺 101 和完全包含的分组，查询店铺 102 返回 403。
- 定向测试：`BusinessAnalyticsServiceTest` 7 项、`QaBusinessAnalyticsControllerTest` 2 项通过；完整后端回归 168 项通过、0 失败/错误/跳过；前端类型检查通过；最终 Vite 352 模块生产构建与裸机 3000 部署成功。

## 批次 12：V4 需求重建、IM 幂等与商品知识版本（v2.7.1 候选）

### 基线与当前范围

- 逐页点击现有一级路由及关键二/三级界面，将实际界面证据、功能真实性、数据口径和安全降级记录到 `XIANYU_MATRIX_V4_FUNCTION_AUDIT_2026-09-15.md`。
- 新增 `XIANYU_MATRIX_V4_PRODUCT_REQUIREMENTS.md` 与 `XIANYU_MATRIX_V4_LONG_TERM_EXECUTION_PLAN.md`；V4 为现行长期开发合同，原 PRD/PUB/ORD/IM 专项验收继续有效，不以新文档撤销历史缺陷。
- 本次代码仅收口 `XYM-IM-001`、`IM-04`与 `BASE-06`；不宣称 V4 全部功能已完成。

### 功能与数据迁移

- `V44__ai_handoff_open_attempt_token.sql`：转人工任务增加打开尝试所有权标识，避免 MySQL affected-row 语义导致首次请求被误判为重放。
- `V45__goods_knowledge_versions.sql`：增加商品知识版本、版本操作请求幂等表，并在自动回复记录中保存知识版本 ID/版本号。
- `V46__goods_knowledge_request_fingerprint.sql`：为保存请求增加 SHA-256 载荷指纹和内部尝试所有权标识，修复 `XYM-IM-004` 异载荷重放与并发单赢家语义。
- `GoodsKnowledgeService`：支持草稿、启用、失效、生失效窗口、并发版本号、同请求精确重放与异载荷 409，写入商品事件和统一审计。
- AI 与关键词润色策略改为只消费当前有效版本；过期/已停用内容不再从旧 `fixed_material` 泄漏进提示词。
- 前端增加软件商品模板、草稿/立即启用、有效期、版本历史、启用/停用、持久错误与重试；扩展语义资料保持 AI/Embedding 未配置时的明确降级。
- 共享布局对真实 `.app-main` 容器执行路由滚动复位，修复鼠标滚轮滑到下方后又回顶。

### 验证结果

- 完整后端回归：`scripts/local-toolchain.sh ./mvnw -Dmaven.repo.local=/Volumes/Data/codex/xianyu/.tools/m2 test`，最终 187 项通过，0 失败、0 错误、0 跳过。
- 前端：`npm run type-check` 通过；`npm run build-only` 通过，352 个模块；最终静态资源已从当前源码重新生成。
- 打包：`./mvnw -DskipTests package` 成功；裸机 macOS 应用启动于 `127.0.0.1:3000`，Docker 仅运行 MySQL 5.7 `127.0.0.1:13306`。
- 升级验证：MySQL 5.7 日志证明现有 schema 从 V43 成功应用 V44，再从 V44 应用 V45/V46；最终验证 46 个迁移并处于 schema V46。
- 隔离 API E2E：`qa-im-idem-v271-20260915` 首次任务 ID 12、`idempotentReplay=false`，重放同任务且为 `true`；响应明确 `platformNetworkCalls=false`、`aiNetworkCalls=false`。
- 知识版本 E2E：草稿/启用/停用及各自重放通过；最终为 `NO_EFFECTIVE_VERSION`、保留 1 条 `EXPIRED` 历史。创建、启用、停用 requestId 的统一审计总数各为 1。
- Product Design 开发侧 QA：实际页面验证商品选择、版本空态、已停用历史、有效期、保存策略、安全降级和 tab/tabpanel 语义；窄屏鼠标滚轮可停在页面底部，路由切换后新页面回到顶部。截图不替代键盘、对比度和读屏工具验证。

### 残余降级和安全边界

- 扩展语义检索需要配置 AI/Embedding；本地商品知识版本不依赖该服务。
- 未调用真实 AI、闲鱼、企业微信或邮件；测试数据只在 Tenant-A 账号 101 与 `QA-` 商品/会话范围。
- 本地内置浏览器当次无法产生真实 4K CSS 视口；4K 依赖此前证据与静态响应式检查，需独立测试再次实机验证。

### 独立测试缺陷回归（XYM-IM-004/005）

- `XYM-IM-004`：根因为旧重放分支只比较账号和商品。现以长度编码后的账号、商品、内容、原始生效时间、失效时间、启用方式和来源生成 SHA-256；异载荷返回 409。五路并发 E2E 全部 code 200，版本 ID 唯一、首次 1/重放 4，统一审计 total=1。
- 并发回归曾发现 MySQL `REPEATABLE READ` 下锁定读取到赢家后，普通快照查询仍看不到新行。版本回读改为当前锁定读，消除并发中的两个 500；不用重试掩盖该竞态。
- `XYM-IM-005`：DTO 局部接受 `yyyy-MM-dd'T'HH:mm[:ss][.SSS]`；新增用例覆盖分钟/秒/毫秒和非法格式，全局 `HttpMessageNotReadableException` 映射为可读 400。API E2E 证明 ISO 毫秒可保存，非法生失效区间返回 400，坏格式也返回 400。

## 批次 13：V5 全功能证据重建与 Wave 0 首批修复（v2.7.2 候选）

### 需求与审查基线

- 使用 Product Design 截图优先流程点击/查看29个主路由与隐藏兼容路由、74组主要二三级状态；证据、限制和结论记录在 `XIANYU_MATRIX_V5_SCREEN_EVIDENCE_AUDIT_2026-09-15.md`。
- 新增 `XIANYU_MATRIX_V5_PRODUCT_REQUIREMENTS.md` 与 `XIANYU_MATRIX_V5_LONG_TERM_EXECUTION_PLAN.md`，以V5编号重新定义长期目标、七个实施波次和硬验收；既有PRD/PUB/ORD/IM门禁继续继承。
- 本批只完成 Wave 0 的可独立回归增量，不宣称V5全部功能、1080P/4K或200%设计QA已经完成。

### 功能变更

- `V5-BASE-07`：裸机部署改为版本化不可变JAR；构建前快照遗留运行制品，构建后复制到`releases`，停止、原子切换、健康检查，失败时回滚旧制品。运行JVM不再读取会被Maven覆盖的`target/*.jar`。
- `V5-SEC-01`：团队账号创建字段每次显式清空，使用独立字段名和`new-password`语义，关闭按钮增加可访问名称；浏览器回归确认用户名/密码为空。
- `V5-IM-08`：客服三收件箱增加`tablist/tab/tabpanel`、`aria-selected`、roving tabindex和左右方向键切换；实际AX树可识别页签，方向键可切至通知消息。
- `V5-BASE-08/V5-OPS-05`：工作流画布使用受控高度、独立滚动和overscroll containment；页面底部收口，不再滚入巨大空白。
- `V5-PRD-03/V5-ORD-03`：新增统一SKU就绪服务。主档声明数、已验证子项数不一致，或0/0没有完整快照证据时，配置保存与自动发货执行均阻断；开启总开关前还必须完成全部SKU配置。前端显示声明/验证差异并禁用保存和开启，不再用前端子项数覆盖主档声明数。

### 变更文件与迁移

- 需求：V5审查、V5 PRD、V5长期计划。
- 后端：`GoodsSkuReadinessService.java`、`AutoDeliveryConfigServiceImpl.java`、`AutoDeliveryServiceImpl.java`、`ItemServiceImpl.java`。
- 前端：团队权限、集成客服、工作流、自动发货及商品类型定义；最终生产静态资源由本批源码重新生成。
- 运行：`scripts/native-qa.sh`。
- 测试：`GoodsSkuReadinessServiceTest.java`、`ItemServiceAutoDeliveryGateTest.java`。
- 迁移：无；数据库仍为V46，兼容MySQL 5.7。

### 测试与设计QA

- `npm run type-check`：通过。
- `npm run build-only`：通过，Vite 352模块；静态资源由最终源码生成。
- SKU/商品/发货定向回归：30项通过；新增SKU就绪与总开关门禁：8项通过。
- 完整后端回归：195项通过，0失败、0错误、0跳过。
- `zsh -n scripts/native-qa.sh`、`git diff --check`：通过。
- 首次受限环境部署在Docker socket处失败，脚本按设计回滚旧不可变制品且3000保持健康；授权环境重跑后成功切换到版本化JAR，健康端点`UP`。
- 浏览器开发侧QA：创建账号用户名/密码为空；客服AX树为标准tab结构且方向键可切换；工作流画布和页面主滚动分离，底部无巨大空白。

### 残余范围与安全边界

- 本轮内嵌浏览器仍无法产出真实1920×1080/3840×2160视口；独立测试需补1366、1080P、4K和200%证据。
- 全站首帧0值、所有长弹窗固定底部、重复入口退场和统一事件中文化属于后续Wave 1，不在本批宣称完成。
- 未执行生产发布、改价、上下架、发货、退款、删除、申诉、消息发送、成员保存或外部通知。

## 批次 14：V5 Wave 1 经营入口收拢与键盘焦点回归（v2.8.0 增量）

### 需求与缺陷

- `V5-IA-01`：将原独立 `/data-panel` 的实时成交、履约与客服信号并入 `/dashboard?view=realtime`，经营罗盘形成“经营总览 / 实时服务”双页签。
- `V5-IA-04`：`/data-panel`、`/automation`、`/pending-orders` 改为只读迁移页；旧地址仍可达，但不再独立取数或执行发货等动作。
- `V5-BASE-01/02`：实时服务与运营驾驶舱首载显示 `—` 和加载状态；无可信样本时不把初始化值解释成业务 0。
- `V5-BASE-09`、`XYM-V5-W0-001`：客服页签切换后等待 Vue DOM 更新再聚焦新页签，支持连续左右方向键与 Home/End；运营驾驶舱和经营罗盘页签使用同一键盘语义。
- `V5-IA-02`：运营驾驶舱保留可执行账号、异常、客服和能力队列；加载失败显示可恢复错误，不与经营罗盘竞争经营统计口径。

### 变更文件、迁移与 API

- 前端：`vue-code/src/views/dashboard/index.vue`、`useDashboard.ts`、`RealtimeServicePanel.vue`、`views/command-center/index.vue`、`views/messages/workspace.vue`、`components/navigation/LegacyRouteNotice.vue`、`views/legacy/*.vue`、路由和菜单配置，以及最终生产静态资源。
- 后端 API：沿用 `/business-analytics/*` 作为经营总览真值，沿用 `/data-panel/*` 作为经营罗盘内部“实时服务”事件视图；没有新增或伪造平台数据。
- 数据库迁移：无；Flyway 仍为 V46，MySQL 5.7 QA 数据库复用现有 schema。

### 测试与 Product Design QA

- `scripts/local-toolchain.sh npm --prefix vue-code run type-check`：通过。
- `scripts/local-toolchain.sh npm --prefix vue-code run build-only`：通过，Vite 355 个模块；最终源码静态资源已重新生成。
- `scripts/local-toolchain.sh ./mvnw test`：195 项通过，0 失败、0 错误、0 跳过。
- `scripts/native-qa.sh deploy`：Maven package 成功，不可变 JAR 原子切换成功；macOS 原生应用 `127.0.0.1:3000` 健康，Docker MySQL 5.7 仅在 `127.0.0.1:13306`。
- 桌面截图：经营罗盘双页签、实时服务来源提示、四项指标和趋势同屏，页面无横向溢出。
- 390×844：三个迁移页单列显示，`scrollWidth=innerWidth=390`；集成客服无横向溢出，核心处置仍可见。
- 键盘回归：客服焦点实际按“买家会话→通知消息→AI 待接管”连续移动，`aria-selected`、`tabindex`、焦点和 `tabpanel` 一致；经营罗盘与驾驶舱页签也能用方向键切换并聚焦。
- 独立测试即时交互回传 `XYM-V5-W0-001` 修复不完整：原实现仍等待通知/接管接口后才聚焦。最终修正为先同步切换模式，`nextTick` 后立即聚焦，再让面板请求独立异步执行；慢请求、失败或超时不再阻塞连续方向键。
- 独立测试进一步发现同源缺陷 `XYM-V5-W1-001`：运营驾驶舱“客服队列/能力矩阵”也等待接口才聚焦。已将页签选择与面板加载解耦，并为两类异步读取补充可见错误语义，连续方向键不再受接口时延影响。
- `XYM-V5-W1-002`：无 Cookie 属于隔离账号的预期安全降级。会话买家增量资料查询改为静默、已捕获且单会话单次尝试，界面明确说明正在使用本地消息名称，避免轮询重复请求、全局错误提示和未处理控制台异常。
- 旧待发货页验证：checkbox=0，精确名称“刷新”“发货”旧按钮均为0，只保留订单中心、自动发货配置和履约异常三个安全去向。

### 残余范围与安全边界

- 本批完成 Wave 1 的入口唯一化增量，不宣称统一任务模型、全局事件字典、1000 条虚拟化、1080P/4K/200%或七类状态全部完成。
- `/data-panel` 后端事件接口暂保留供经营罗盘实时页签消费；后续需统一 `DataEvidence` 响应元数据，不能仅以 `hasData` 推断各个指标覆盖。
- 未点击运营异常的认领/解决/忽略，也未执行生产发布、改价、上下架、发货、退款、删除、申诉、消息发送、权限保存或外部通知。
## 批次 15：V6 全功能重审与长期基线重建（进行中）

### Product Design 点击证据

- 以路由表为清单重新进入 29 个主业务/隐藏兼容入口，点击代表性详情、页签、弹窗、空态、错误态、部分成功和结果未知状态。
- 新增 `XIANYU_MATRIX_V6_FEATURE_EVIDENCE_AUDIT_2026-09-15.md`，记录 50 组功能证据和 6 组响应式/滚动证据；当前浏览器截图只保存在任务证据流，未伪造仓库图片链接。
- 真实视口补验：390×844 经营罗盘、账号 360、商品发布、消息中心；1920×1080 商品发布。商品发布长页滚轮从内容区 2160 稳定到 2300.5，本页未复现自动回顶。

### 新发现的先行门禁

- 运营驾驶舱能力矩阵现场返回系统异常。
- 客服稳定态存在 21 个会话和 buyerId，但买家管理稳定态为 0，跨域事实不一致。
- 通知渠道名称和 SMTP 用户名被浏览器错误填入 `designqa_admin`。
- 创建普通用户默认勾选全部功能与高风险权限，不符合最小授权。
- 备份页未显示模块，恢复缺 dry-run、恢复点、回滚和强确认。
- 站内通知一次渲染大量记录，且直出英文状态；订单消息页签为空白卡。

### 需求和长期目标

- 新增 `XIANYU_MATRIX_V6_PRODUCT_REQUIREMENTS.md`，以 V6-BASE/IA/ACC/CON/PRD/PUB/TASK/FUL/ORD/IM/BUY/AI/NOT/DIAG/AUD/RBAC/SEC/BACKUP/SYS 编号定义硬验收。
- 新增 `XIANYU_MATRIX_V6_LONG_TERM_EXECUTION_PLAN.md`，保留 V5 Wave 0/1 已完成能力，将当前 V47 工作树纳入 Wave 2，并增加 Gate 0 修复本轮 P0 事实断裂和治理缺陷。
- V6 继承全部既有专项验收和已关闭缺陷，不以文档换版本撤销历史门禁，也不宣称当前已完成 V6。

### 当前代码与安全边界

- V47 完成统一通知事件 ID、投递状态和渠道密钥加密字段；历史明文密钥由启动迁移器转为密文，读取接口只返回是否已配置，不回显端点与密钥。
- 账号 360 增加运行隔离档案和逐数据集证据；缺历史状态行时，仅允许由真实成功快照派生证据，未知继续显示为未知。
- V48 将能力状态字段从 24 扩至 40 字符，修复 `REQUIRES_PLATFORM_PERMISSION` 落库失败；同时从会话分配回填买家档案，并在后续会话刷新时持续投影。
- 普通用户创建表单改为最小权限默认集，高风险权限默认关闭；通知渠道和 SMTP 字段设置独立名称与正确自动填充语义。
- 能力矩阵、买家档案、账号数据证据、通知渠道和邮箱表单均在最终源码部署后重新进入页面复验。
- 独立测试报告及 `.rescue-work/`、`efi-backups/`、JVM 日志继续作为外部/用户文件保留，不纳入产品提交。
- 本轮没有执行生产发布、上下架、改价、删除、发货、退款、申诉、消息发送、成员保存或外部通知。

### 本批迁移、构建和测试

- 数据库：复用 Docker MySQL 5.7 `127.0.0.1:13306`；Flyway 成功从 V47 迁移到 V48。
- 后端完整回归：`scripts/local-toolchain.sh ./mvnw test`，202 项通过，0 失败、0 错误、0 跳过。
- 前端类型检查：`scripts/local-toolchain.sh npm --prefix vue-code run type-check`，通过。
- 最终生产构建：Vite 355 个模块；静态资源由最终源码重新生成，不采用独立测试任务的中间机械产物。
- 裸机部署：`scripts/native-qa.sh deploy` 成功；不可变制品 `xianyusmart-2.7.1-20260915T002041Z-6e68b199715a.jar`，macOS 应用 `127.0.0.1:3000`，MySQL `127.0.0.1:13306`。
- Product Design 回看：能力矩阵显示 3 个隔离账号的细分能力状态；买家管理显示 17 位买家；账号 101 的运行档案、店铺画像和风险证据来源一致；SMTP 用户名为空。

### 未关闭的 Gate 0

- `V6-BUG-005`：延迟等待后确认六个模块真实存在，原“完全空白”是无加载反馈导致的稳定态前误判；加载/错误态、依赖/数量/范围/大小、manifest、dry-run、恢复点、回滚和强确认仍需完整开发。
- `V6-BUG-006` 已完成：复用后端真实分页，前端默认每页 20 条，增加通知状态、店铺和关键字筛选、总量/范围说明与跨页控件；真实 88 条数据为 5 页，第二页加载通过，390×844 单列设计 QA 通过。
- 因此当前只声明 V6 证据基线、Wave 2 增量和五项 Gate 0 缺陷闭环，不宣称 V6 或 Gate 0 全部完成。

## 批次 16：V6 Gate 0 安全备份恢复（v2.8.1 候选）

### 需求与缺陷

- `V6-BUG-005 / V6-BACKUP-01`：模块稳定态之前增加明确加载、错误和重试；每个模块展示依赖、真实记录数、当前经营主体范围、预计大小和敏感数据标志。
- `V6-BACKUP-02`：备份格式升级为 2.0 manifest，包含应用版本、Flyway schema、租户、导出时间/操作者、加密标志、模块依赖、数量、大小、逐模块及总 payload SHA-256。
- `V6-BACKUP-03`：停用旧直接导入，新增 20 分钟持久化预检令牌；版本、跨租户、payload/模块篡改、依赖缺失和文件变化均在任何业务写入之前拒绝，并输出保守新增/覆盖估算。
- `V6-BACKUP-04`：V49 新增恢复主任务和逐模块恢复点；执行领取、业务写入与成功状态使用事务，处理器单行失败会使整次事务失败；任务、站内事件和统一审计可追踪，成功后可使用强确认从恢复点回滚。
- `V6-BACKUP-05`：预检、执行和回滚均要求系统写权限；执行要求精确输入“恢复 N 个模块”，回滚要求精确输入“回滚恢复任务 ID”；无权限界面不能选择文件或进入写链路。

### 隔离验收能力

- 默认关闭 `BACKUP_QA_MOCK_ENABLED`；裸机 QA 仅对明确白名单租户开启 `qaRestoreProbe`，只写 `xianyu_backup_restore_probe` 专用表，可验证同一正式恢复编排的预检、执行、幂等、恢复点和回滚，平台网络调用为零。
- `/api/qa/notification-trace/fixture` 生成终态 `FAILED / NOT_SENT_QA` 的持久化通知链。通道为禁用状态，调度器不会领取；inbox、outbox、delivery log 使用同一 eventId，外部网络调用为零。
- 本批没有对账号、商品、卡密、订单、消息或任何闲鱼平台对象执行恢复，也没有发送真实外部通知。

### 变更、迁移与 API

- 迁移：`V49__safe_backup_restore_jobs.sql`，新增恢复主任务、逐模块 LONGTEXT 恢复点和隔离 QA 探针表；没有修改已发布迁移。
- API：`POST /api/backup/restore/preview`、`POST /api/backup/restore/execute`、`GET /api/backup/restore/jobs/{jobId}`、`POST /api/backup/restore/jobs/{jobId}/rollback`；旧 `POST /api/backup/import` 明确拒绝直接导入。
- QA：`POST /api/qa/backup/fixture`、`GET /api/qa/backup/probe`、`POST /api/qa/notification-trace/fixture`，均受 QA 开关、租户白名单、菜单和系统写权限保护。
- 前端：设置页安全恢复工作台、模块依赖选择、manifest/冲突结果、精确确认、持久任务状态、逐模块恢复点和手机布局；最终静态资源只由本批最终源码生成。

### 最终验证结果与独立验收边界

- 后端完整回归：212 项通过，0 失败、0 错误、0 跳过；备份与权限定向回归覆盖 manifest、跨租户、旧包、篡改、缺依赖、超过 50 MB、强确认、处理器吞错和越权。
- 前端类型检查通过；Vite 生产构建 355 模块通过；最终静态资源由本批最终源码重新生成；`zsh -n scripts/native-qa.sh` 与 `git diff --check` 通过。
- MySQL 5.7 升级库由 V48 成功迁移至 V49；另用无卷临时 MySQL 5.7.18 从 V1 完整迁移至 V49，确认 V49 三张表存在，全程未改变现有数据库权限。
- 裸机不可变候选制品部署于 `127.0.0.1:3000`，Docker 只复用 MySQL `127.0.0.1:13306`；健康检查为 UP。
- 隔离恢复 E2E 仅写专用探针：预检不写数据、执行成功、相同请求幂等重放、生成恢复点、精确确认回滚成功；注入失败时任务持久化为 FAILED 且事务回滚，探针值保持恢复前状态。
- 通知夹具生成 `FAILED / NOT_SENT_QA` 终态，inbox、outbox、delivery log 共用同一 eventId，禁用本地通道且外部网络调用为 0。
- Product Design QA：1920×1080、3840×2160、390×844、加载/稳定/大数据量与滚轮通过，无横向溢出或自动回顶；错误和无权限态由实现与自动化门禁覆盖，未为截图断开应用或保存成员权限。
- 已知限制：模块数量/大小来自真实数据快照，在 1009 条商品数据下可能加载十余秒；界面显示真实加载态，不显示虚假 0 或模拟进度。业务处理器使用安全合并语义，恢复点可还原既有记录，但本批不宣称能自动删除恢复过程中新建的业务记录。
- 独立测试已在冻结提交 `266e90b` / `v2.8.1-rc.1` 上完成回归：恢复成功/失败/幂等/强确认/重启恢复、OPERATOR 403、Tenant-B 404、通知三表同 eventId、390×844 页面和构建门禁均通过；未发现新缺陷，Gate 0 无未决 P0/P1。
- 正式标签 `v2.8.1` 已指向冻结提交并推送；本结论只关闭 Gate 0 / 当前 Wave 2 增量，不声明 V6 Wave 3～8 完成。

## 批次 17：V6 Wave 3 统一全屏 360 与可读事件（v3.0.0 候选）

### 需求编号与交付结果

- `V6-BASE-08/09/10/13/14`：商品、订单、买家、商品任务和审计详情统一为最大 1440×900 的居中工作区；支持详情深链接、页签、筛选/分页/滚动恢复、Escape 与焦点陷阱；业务文字中文化，机器码和 JSON 默认折叠。
- `V6-PRD-02/06`：商品多 SKU 的声明数和验证数分开表达；4 SKU 隔离夹具真实展示规格、价格、库存、平台状态与履约映射；未同步不显示为 0。
- `V6-ORD-02/06`：订单 360 保留物流、退款、消息和时间线；能力元数据不参与能力卡渲染，正式能力及来源/限制中文可读。
- `V6-BUY-02`：买家 360 保留身份、订单、会话和跟进上下文，支持详情 URL 和手机单滚动。
- `V6-AUD-02/04`：统一审计增加对象/请求 ID、类型、模块、状态、结果、操作者和时间筛选；深链接确定性打开；脱敏 CSV 导出使用独立 `exportRequestId` 并留下审计，不再把导出请求 ID 错当查询条件。

### 变更文件、迁移与 API

- 前端：`vue-code/src/views/goods/index.vue`、`orders/index.vue`、`buyers/index.vue`、`product-tasks/index.vue`、`operation-log/*`、`api/operation-log.ts`、`composables/useModalFocusTrap.ts`，以及最终生产静态资源。
- 后端：`OperationLogController`、`OperationLogService`、`OperationLogServiceImpl` 和对应测试；审计 CSV 导出参数职责分离。
- 版本：Maven、前端包和页面版本升级至 `3.0.0`。
- 数据库迁移：无；复用已验证的 Flyway V49 schema，不修改任何已发布迁移。

### 测试、部署与 Product Design QA

- 后端完整回归：212 项通过，0 失败、0 错误、0 跳过；审计导出定向测试覆盖独立导出请求 ID。
- 前端类型检查通过；最终 Vite 生产构建 356 模块通过；静态资源由最终源码重新生成，不采用独立测试任务的中间产物。
- 裸机不可变制品部署成功：`xianyusmart-3.0.0-20260915T030018Z-02af69380b4b.jar`；应用 `127.0.0.1:3000` 健康，Docker 仅运行 MySQL `127.0.0.1:13306`。
- 1366×768 商品详情 `1322×724`、4 个真实 SKU、无横向溢出；3840×2160 订单详情 `1440×900` 精确居中；390×844 审计详情无横向溢出并可滚动。
- 深链接回归：商品、订单、买家、任务、审计均保留对象 ID/页签；审计复制链接首载竞态已修复。
- 交互回归：滚轮内容滚动、Escape 关闭、焦点陷阱和关闭后的 URL/列表上下文恢复通过；最终浏览器控制台无新增 error/warn。
- Product Design 采用参考图的信息架构、密度和上下文保持，不复制其品牌、文案或素材；详细结果见 `design-qa.md`，结论为 passed。

### 残余安全降级

- 实物运单平台写入、订单改价、退款同意/拒绝、平台原生收货提醒、平台营销和商品罗盘主动同步仍缺可靠适配器，继续明确标记“尚未验证 / 不可用 / 仅本地 / 未接入”。
- 本批没有执行生产发布、改价、上下架、发货、退款、删除、申诉、消息发送、成员保存或外部通知；不以模拟前端进度替代后端持久状态。
- 本批完成 Wave 3，不代表 V6 Wave 4～8 已完成。

## 批次 18：全功能点击审计基线与 Wave 3 回归闭环（v3.0.1 候选）

### 需求基线与交付范围

- `V6-CLICK-AUDIT`：按登录后的实际菜单逐项点击、等待稳定态并记录证据，形成 `XIANYU_MATRIX_V6_CURRENT_SYSTEM_CLICK_AUDIT_2026-09-15.md`；35 条桌面证据、6 条响应式证据、4 个独立测试缺陷和 12 个后续缺口均绑定页面、风险与验收结果。
- `V6-CLICK-REQ-01～12`：写入 V6 产品需求；长期实施计划升级为 6.1，在 Wave 4 前增加 Gate 3.1。后续开发必须从该点击真值出发，不再只按菜单是否存在判断能力完成。
- `W3-BUG-001`：买家订单金额或数量证据未同步时显示 `—` 与覆盖说明，不再把未知事实伪装成 `¥0.00` 或 `0`。
- `W3-BUG-002`：消息时间兼容数字字符串 epoch，统一输出可读本地时间。
- `W3-BUG-003`：商品 360 在 `640×360` / 200% 等效窄高环境仍能看到栏目、内容和底部操作，单内容区可滚动。
- `W3-BUG-004`：买家 360 增加客户黑名单状态、来源、时间、保存动作和客服会话直达；消息侧黑名单与买家档案同账号同买家投影一致。

### 变更、迁移与接口

- 前端：买家管理、消息工作台、商品 360、买家 API 类型，以及由最终源码重新生成的生产静态资源。
- 后端：买家档案实体/DTO/Mapper/Service、消息工作台服务；黑名单会强制阻断自动回复，解除会话侧黑名单只清除 `[会话]` 来源的阻断，不覆盖其他人工阻断原因。
- 迁移：`V50__buyer_blacklist_projection.sql`，为买家档案增加黑名单事实、来源、更新时间和索引，并从会话分配事实安全回填。
- API：复用买家 360 读取/保存和消息会话接口；买家保存响应增加黑名单字段，客服直达使用 `accountId + buyerId` 深链接并等待会话稳定加载后选中。
- 版本：Maven、前端包和运行版本升级至 `3.0.1`。

### 测试、部署与 Product Design QA

- 定向后端回归：`BuyerProfileServiceTest,MessageWorkspaceServiceTest` 共 7 项通过，0 失败、0 错误、0 跳过。
- 后端完整回归：216 项通过，0 失败、0 错误、0 跳过。
- 前端类型检查通过；Vite 最终生产构建 356 模块通过；静态资源只采用最终源码产物。
- 裸机不可变制品：`/Volumes/Data/codex/xianyu/.tools/native-qa/releases/xianyusmart-3.0.1-20260915T042037Z-bf214292f950.jar`；应用健康检查为 `UP`，Docker 仅运行 MySQL。
- Flyway 在 MySQL 5.7 验证 50 个迁移并由 V49 升级至 V50。
- Product Design 实机 QA：买家列表未知金额、可读时间、黑名单保存/回读/解除、客服深链接，以及 `640×360` 商品 360 栏目和 4 个真实 SKU 均通过；QA 黑名单状态已恢复。
- 安全边界：只使用 Tenant 1、账号 101 的 QA 买家和 QA 商品；未执行真实平台发布、改价、上下架、发货、退款、删除、申诉、消息发送或外部通知。

### 残余门禁

- 本批只关闭 `W3-BUG-001～004` 并建立点击审计需求真值，不宣称 Gate 3.1 或 V6 后续波次全部完成。
- `V6-CLICK-001～012` 继续按依赖顺序纳入 Gate 3.1；每个条目必须具备稳定加载/错误/空态、权限和大数据量证据后才能关闭。

## 批次 19：Gate 3.1 第一批入口恢复与买家审计闭环（v3.0.2 候选）

### 需求与缺陷

- `W3-BUG-005 / V6-CLICK-REQ-09 / V6-AUD-01`：买家 360 保存升级为持久化幂等事务；同一 `requestId` 绑定规范化载荷指纹，完全一致只回放已存结果，不重复更新或记日志；同请求不同内容返回 409。
- 买家资料、会话黑名单投影、请求记录和强制审计在同一事务提交。审计写入失败会抛出并回滚，不能再用“吞掉日志异常”的弱审计路径。
- 统一审计记录操作者、账号、目标买家、请求 ID、幂等键、来源、结果，以及买家名称、标签、备注、人工暂停、拦截原因、黑名单、来源和更新时间的逐字段 `before/after`。
- `V6-CLICK-001/004/008/009`：修复连接账号卡与详情不同步、运营四主栏目不切换、诊断五页签空白、设置七分区正文不切换；所有入口同时保存 URL 查询参数并支持浏览器前进后退。
- `V6-CLICK-REQ-01/03`：新增通用异步资源状态组合，区分首载、刷新、成功、真空态、错误和无权限；运营/诊断页刷新保留上次成功内容并用请求修订号阻止旧响应覆盖新页签。

### 变更、迁移与 API

- 后端：买家保存 DTO/Service、强制审计接口、幂等请求实体与 Mapper；`POST /api/buyers/save` 新增必填 `requestId` 和可选 `idempotencyKey`。
- 迁移：`V51__buyer_profile_idempotency_audit.sql`，新增租户级唯一请求表和账号/买家检索索引；MySQL 5.7 由 V50 成功升级到 V51。
- 前端：买家保存自动生成请求 ID；连接管理账号卡/详情深链；运营、通知诊断、系统设置状态与 URL；新增 `useAsyncResourceState`；手机设置栏目压缩为横向导航；运营批量账号控件压缩并补可访问名称。
- 版本：Maven、应用运行版本和前端包统一升级至 `3.0.2`；最终静态资源由最终源码重新生成。

### 自动化、E2E、部署与设计 QA

- 后端完整回归：219 项通过，0 失败、0 错误、0 跳过；新增覆盖黑名单新增/解除、字段 diff、原请求回放、载荷冲突和强制审计失败不完成请求。
- 前端类型检查通过；Vite 生产构建 357 modules transformed。
- QA API/DB 路径：`QA-BUYER-0` 首写 200、原请求重放 200、冲突载荷 409；同请求审计仅 1 条，差异包含 `blacklisted/automationBlocked/blockedReason/blacklistUpdatedTime`；验证后黑名单、人工暂停和原因均恢复原值。
- 裸机不可变制品：`/Volumes/Data/codex/xianyu/.tools/native-qa/releases/xianyusmart-3.0.2-20260915T050249Z-210fcdf49140.jar`；`127.0.0.1:3000` 健康为 UP，运行版本 `3.0.2`，Docker 仅运行 MySQL `127.0.0.1:13306`；缺少买家写请求 ID 时 API 返回 400 而非系统异常。
- Product Design 复验：桌面连接/运营/诊断/设置，以及 390×844 通知渠道、设置、运营中心通过；页面 URL、选中态和正文一致，设置手机栏目不再占据半屏；最终控制台无 warning/error。

### 残余门禁

- Gate 3.1 尚未完成：`V6-CLICK-002/003/005/006/007/010/011/012` 以及全部错误、无权限、大数据截图回归仍需继续；本批不宣称 V6 长期目标完成。
- 本批未执行真实发布、批量发布、改价、上下架、发货、退款、删除、申诉、消息发送、成员保存或外部通知；连接、运营和设置页面只读点击未触发平台动作。

## 批次 20：Gate 3.1 第二批上下文、二维码与手机客服（v3.0.3 候选）

### 需求与缺陷

- `V6-CLICK-005 / V6-CLICK-REQ-02/04`：订单页接受 `orderId`、`buyerId` 深链筛选；唯一订单结果自动打开真实全屏详情，300 条 QA 订单下列表、详情和来源一致。
- `V6-CLICK-006 / V6-CLICK-REQ-03`：统一在账号 API 边界把后端 Long 字符串归一化为 JavaScript 安全整数，避免客服、订单、买家等页面严格比较时回退到第一家店铺；客服页签、刷新和 URL 始终保留账号范围。
- `V6-CLICK-007 / V6-CLICK-REQ-01/02`：买家列表区分首载、真实空态、错误和刷新错误；错误可重试，刷新失败保留上次成功结果；`accountId + buyerAccountId + buyerId` 可直达买家全链路档案。
- `V6-CLICK-011 / V6-ACC-04 / V6-CLICK-REQ-10`：二维码响应新增后端权威 `generatedAt`；新接入与已有账号续期分流，续期同屏展示闲鱼账号 ID、系统店铺 ID、账号备注、生成/本地失效时间、倒计时和刷新。平台有效期不可由本系统延长，界面明确提示可能提前失效。
- `V6-CLICK-012 / V6-CLICK-REQ-08`：390 手机客服改为列表→会话→资料三阶段，每阶段只显示当前主区并具备返回路径。
- 点击补漏：客服到买家/订单链接带完整账号与对象上下文；订单协作旗帜 `NONE` 显示“无标记”，不再与退款状态“无退款”混用。

### 变更、迁移与 API

- 后端：`QRLoginResponse` 增加 `generatedAt` 并保留兼容构造器；二维码服务返回同一会话的权威生成/失效窗口；新增 DTO 回归测试。
- 前端：账号 ID 归一化、客服页签与手机阶段、买家异步状态和深链、订单对象深链、连接续期/新账号二维码信息、最终生产静态资源。
- API：二维码生成响应增加可选 `generatedAt`，`expiresAt` 继续表示本地监控截止而非平台承诺；其余接口不破坏兼容性。
- 迁移：无新增迁移；启动时 MySQL 5.7 成功验证 51 个迁移，当前 schema version 51。
- 文档：点击审计、V6 产品需求和长期实施计划升级到 6.3；追加 PD-040～045、PD-R10 和本批设计 QA。

### 测试、部署与 Product Design QA

- 前端类型检查：通过；最终 Vite 生产构建 `357 modules transformed`，静态资产由最终 3.0.3 源码重新生成。
- 后端完整回归：220 项通过，0 失败、0 错误、0 跳过；包含二维码权威时间窗口、凭据续期窗口和通知图片存储回归。
- 裸机不可变制品：`/Volumes/Data/codex/xianyu/.tools/native-qa/releases/xianyusmart-3.0.3-20260915T055146Z-a1feb69c1e14.jar`；`127.0.0.1:3000` 运行，Docker 仅运行 MySQL `127.0.0.1:13306`。
- 浏览器运行态：订单 0091 深链自动打开；买家 4 深链展示 10 单/14 消息/10 商品；客服账号 102 跨页签保留；390×844 三阶段导航通过；续期二维码展示 14:59 倒计时和完整身份；最终部署后控制台无新增 warning/error。
- 安全边界：二维码生成后未扫码；未发送真实买家消息，未保存会话设置，未执行发布、改价、上下架、发货、退款、删除、申诉、成员保存或外部通知。

### 残余门禁

- Gate 3.1 仍未完成：`V6-CLICK-002` 商品首载错误 0/竞态、`V6-CLICK-003` 商品任务范围解释、`V6-CLICK-010` 团队与权限加载仍待开发和独立验收。
- 本批为开发侧候选，不替代独立测试结论，也不代表 V6 Wave 4～8 完成。

## 批次 21：Gate 3.1 客服路由账号隔离回归（v3.0.4 候选）

### 缺陷与修复

- `G31-BUG-001 / V6-CLICK-REQ-03 / V6-IM-01`：客服工作台改为先解析 URL 中的 `accountId/inbox`、再从当前成员可见账号中授权确认，最后才读取消息、商品、会话、通知和 AI 待接管；初始化期间只显示账号范围骨架。
- 消息、商品、会话列表和待接管请求增加账号快照与修订号；快速切换时，旧账号的延迟响应被废弃，不能覆盖新账号。
- 刷新时保留 `inbox=handoffs/notifications`；`buyerId` 只在买家会话页签解析，不再把待接管强制切回会话。
- 未授权的路由账号显示独立无权限态，保留原 URL 供排查，不回退首店、不启动轮询、不展示任何店铺业务数据。

### 变更文件、迁移与 API

- 前端：`vue-code/src/views/messages/workspace.vue`、`vue-code/src/views/messages/useMessageManager.ts`，以及由最终源码重新生成的 `src/main/resources/static` 生产资源。
- 文档：V6 需求、点击审计、长期计划、实施日志和 Product Design QA 升级到 6.4 / 3.0.4 候选基线。
- 迁移：无；不修改已发布的 51 个 Flyway 迁移。MySQL 5.7 启动验证 `Successfully validated 51 migrations`，schema version 51，无待执行迁移。
- API：无新增或破坏性变更；只收紧前端路由、授权和异步响应应用顺序。
- 版本：Maven、前端包和运行版本统一升级至 `3.0.4`。

### 测试、部署与 Product Design QA

- 前端类型检查通过；Vite 最终生产构建 `357 modules transformed`。
- 后端完整回归：220 项通过，0 失败、0 错误、0 跳过。
- 裸机不可变制品：`/Volumes/Data/codex/xianyu/.tools/native-qa/releases/xianyusmart-3.0.4-20260915T061716Z-6e83a4a9469e.jar`；应用 `127.0.0.1:3000` 健康，Docker 仅运行 MySQL `127.0.0.1:13306`。
- 浏览器回归：缺陷原始刷新路径、通知↔待接管前进后退、101→102 快速切换、账号 201 无权限路径、390×844 窄屏均通过；部署后无新增控制台 warning/error。
- 安全边界：本批只读使用 Tenant-A 账号 101/102 和未授权路由 201；未发送买家消息，未保存会话或成员，未执行发布、改价、上下架、发货、退款、删除、申诉或外部通知。

### 残余门禁

- 独立测试已在 `v3.0.4-rc.1 / 5ea4ca0b` 上完成 `QA-G31-MSG-ACCOUNT-001`：`G31-BUG-001` Closed / Pass，定向控制台 warning/error=0，类型检查、357 模块生产构建、220 项后端测试、51 迁移验证、HEAD/tag 和 JAR SHA-256 全部匹配。Gate 3.1 当前无未决 P0/P1。
- V6 Wave 4～8 仍按长期计划待实施；本批不宣称长期目标完成。

## 批次 22：真实账号接入事实一致性（v3.0.5）

### 需求与修复

- `V6-REAL-001 / V6-ACC-01 / V6-CLICK-REQ-04`：账号矩阵不再只依赖 `xianyu_account_access_channel` 快照；当前 WebSocket 健康与最新凭证状态投影为 `MESSAGE_WS / LOCAL_RUNTIME` 实况证据，冲突时优先展示实况。
- 实况证据只声明会话连接和凭证是否存在，返回来源、核验时间、最近成功和 token 到期时间；不推断店铺画像、风险、商品、订单或平台写能力。Cookie、Token 和浏览器存储不进入响应。
- `V6-REAL-002 / V6-CLICK-REQ-06`：连接操作日志复用统一时间格式化器，兼容 number epoch、数字字符串 epoch、ISO 和非法值。
- 账号矩阵列表和全屏店铺档案展示“运行时实测”来源与核验时间，避免运营把陈旧快照当当前状态。

### 变更、迁移与 API

- 后端：`AccountMatrixService` 合并持久通道快照与当前运行实况；新增无凭据泄漏及未知事实不扩张回归。
- 前端：账号矩阵增加 `connectionSource/connectionLastCheckedTime`；店铺档案展示接入证据来源；连接日志修复非法日期；最终生产静态资源由 3.0.5 源码重新生成。
- API：账号矩阵列表/详情新增可选 `connectionSource`、`connectionLastCheckedTime`，`accessChannels` 可含 `LOCAL_RUNTIME` 通道；均为向后兼容的只读字段。
- 迁移：无新增迁移；MySQL 5.7 启动成功验证 51 个迁移，schema version 51。
- 版本：Maven、应用与前端包统一为 `3.0.5`；源码提交 `9e47050`。

### 测试、部署与 Product Design QA

- 后端完整回归：222 项通过，0 失败、0 错误、0 跳过；新增 2 项运行实况/凭据脱敏测试。
- 前端类型检查通过；Vite 最终生产构建 `357 modules transformed`。
- 不可变制品：`/Volumes/Data/codex/xianyu/.tools/native-qa/releases/xianyusmart-3.0.5-20260915T065007Z-1c9a2b230c7a.jar`；SHA-256 `1c9a2b230c7a98bd02b389ea67171cb3fcc64130f94b849ad488ce44aba0f128`。
- 裸机应用 `127.0.0.1:3000` 健康为 `UP`，Docker 仅运行 MySQL `127.0.0.1:13306`；两个真实账号在部署后均自动恢复 WebSocket 连接。
- 浏览器只读 QA：桌面账号矩阵、全屏店铺档案、两个连接详情和 390×844 窄屏均通过；连接日志无 `Invalid Date`，实况与未知数据不再冲突。

### 安全边界与残余项

- 未在仓库记录真实账号编号或凭据；未执行真实发布、改价、上下架、库存、删除、发货、退款、申诉、买家消息、成员保存、二维码扫码或外部通知。
- 两个真实账号的店铺画像、处罚与平台发布能力尚未同步，继续显示“未同步/—”；这不是 0，也不宣称平台适配已完成。
- V6 长期目标仍为 active；下一阶段按计划进入 Wave 4，不能把本批事实修复解释为商品发布能力已完成。

### 独立回归与转正

- 独立测试在 `v3.0.5-rc.1 / a6ebf80` 上确认 `V6-REAL-001/002` 均 Pass，无未决 P0/P1；桌面、390×844、首载、Escape 回焦、实时只读 API、敏感字段、控制台和工程门禁全部通过。
- `V6-REAL-BLK-001` 已关闭为受限终端无法观察 launchd/Docker/本机端口导致的环境误报；开发侧同一时刻的 PID、Docker 状态和定期保活日志证明实例持续运行。
- 独立报告由测试任务保存为 `V6_REAL_001_002_INDEPENDENT_REGRESSION_REPORT.md`；正式标签升级为 `v3.0.5`，V6 长期目标继续保持 active。

## 批次 23：Wave 4 商品发布工作台候选（v3.1.0-rc.1）

- 需求：`V6-PUB-01～08`，并回归 `V6-PRD-01～06`、`V6-TASK-01～06` 的租户、幂等、审计和状态证据边界。
- 数据模型：新增 `V52__listing_catalog_versions_and_preflight.sql`，落地版本化行业/叶子类目/动态属性目录、草稿载荷指纹、不可变草稿版本和 15 分钟发布预检快照；MySQL 5.7 已从 v51 成功迁移到 v52，重启校验通过。
- API：表单 schema 返回目录版本、三种商品类型及履约要求；新增 `GET /api/publishing/drafts/{id}/versions`；预检返回并持久化 `previewToken/expiresAt/catalogVersion/payloadFingerprint/capabilityFingerprint/platformDifferences`；执行必须消费同账号、同请求、同载荷、同目录和同能力快照。
- 后端：完整校验实物/虚拟/服务分型、标题详情、1～9 图、视频、两位小数、原价、库存、outerId、最多 2 维/50 SKU、逐 SKU 价格/库存/编码/图片和动态必填属性；真实通道未适配的高级字段继续安全阻断。
- 前端：三种商品类型、行业→叶子类目→动态属性、分型履约字段、图片封面/排序、视频上传、批量 SKU 填充、自动保存、版本历史、字段错误定位、同源手机预览、预检凭证和平台差异证据、手机表单/预览切换。
- QA 修复：商家运营租户解析由操作者 ID 改为认证租户 ID；MySQL `DATETIME` 同时兼容 `LocalDateTime/Timestamp`；载入草稿同步显示已保存版本。
- 安全 E2E：仅 Tenant 1 / 账号 101 / `QA-PUBLISH-*` / `QA_LOCAL`。库存变更使旧凭证失效；重新预检后生成任务 20、`QA-PUBLISHED-20`、`QA_CONFIRMED`，平台写入 `NOT_PERFORMED`；同请求重放仍返回任务 20。
- 测试：`./mvnw -q test` 为 227/227 通过；前端 `vue-tsc --build` 通过；Vite 357 modules 生产构建通过；Flyway 校验 52 个迁移并确认 schema v52；`git diff --check` 通过。
- 设计 QA：1920×1080、390×844、3840×2160；空草稿、错误、已保存、预检、令牌失效、发布成功、图片失败和重复提交均已检查；滚轮不回顶。详情见 `design-qa.md`。
- 残余降级：目录是本地版本化参考子集，不是官方完整类目库；真实平台高级字段适配证据不足时禁止真实提交；50 SKU 长表与只读角色留给独立测试回归。
