# XianYuSmart 矩阵需求实施记录

基线：`XIANYU_MATRIX_PRODUCT_REQUIREMENTS.md` 1.0（2026-09-13）
原则：以需求编号、优先级和验收标准为准；保留既有工作树；禁止以 `0` 代替未同步数据；禁止对生产店铺执行发布、退款、删除或申诉验证。

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
