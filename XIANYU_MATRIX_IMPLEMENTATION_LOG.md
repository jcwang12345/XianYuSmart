# XianYuSmart 矩阵需求实施记录

基线：`XIANYU_MATRIX_PRODUCT_REQUIREMENTS.md` 1.0（2026-09-13）
原则：以需求编号、优先级和验收标准为准；保留既有工作树；禁止以 `0` 代替未同步数据；禁止对生产店铺执行发布、退款、删除或申诉验证。

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
