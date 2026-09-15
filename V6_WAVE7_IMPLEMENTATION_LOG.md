# V6 Wave 7 实施记录 — 素材、增长与工作流

## 版本与范围

- version: `3.4.0-rc.1`
- requirements: `V5-OPS-01`～`V5-OPS-05`（V6 Wave 7 继承）、`OPS-01`～`OPS-05`、`V6-BASE-01`～`V6-BASE-15`、`V6-CLICK-REQ-01`～`V6-CLICK-REQ-08` 的相邻回归
- QA runtime: 裸机 macOS Java/Vue，`http://127.0.0.1:3000`
- database: Docker MySQL 5.7，`127.0.0.1:13306`
- safe accounts: 101/102/103 仅用于隔离夹具；真实账号 202/203（外部账号 10599065/39175043）本批只读，不执行发布、改价、上下架、删除或发送消息。
- safety gate: 工作流只允许 `DRY_RUN` 或 QA 隔离执行；发布节点只做结构预检，`platformWrite=NOT_PERFORMED`。

## 需求交付

| 需求 | 交付摘要 |
|---|---|
| V5-OPS-01 / OPS-02 | 素材版本不可变；来源 URL、许可、许可失效、去重指纹、适用账号、商品映射和引用影响可查；未设置价格返回 `null`，不显示为 0。 |
| V5-OPS-02 / OPS-01 / OPS-03 | 商机搜索保存来源、采样时间、授权说明、快照和逐样本证据；比价显示样本数、区间、中位数、异常值和口径；没有可靠证据时不输出确定性定价结论。 |
| V5-OPS-03 | 货源记录供应商、成本、库存、发货时效、退换政策、有效期、商品映射和版本审计；列表 24 条/页并支持搜索。 |
| V5-OPS-04 / OPS-04 | 工作流定义、不可变版本、预检、实例、逐节点输入输出、事件和动作请求全部持久化；支持按请求幂等、失败节点重试、安全取消、结果未知人工核对、重启恢复和可补偿节点补偿。 |
| V5-OPS-05 / OPS-05 | 桌面为定义列表/画布/节点配置三栏；手机为节点列表和任务详情分层；页面与画布滚动分离；1000 次运行分页读取；无巨大空白和自动回顶。 |
| V5-OPS-06 | 没有可靠订单归因、结算来源和平台协议，分销佣金入口继续隐藏，没有用空表冒充完成。 |

## 数据库迁移

- `V56__growth_workspace_versions_and_runs.sql`
  - 新增 `growth_resource_version`、`growth_resource_goods_mapping`、`growth_search_snapshot`。
  - 新增 `growth_workflow_version`、`growth_workflow_run`、`growth_workflow_node_run`、`growth_workflow_event`、`growth_workflow_action_request`。
  - 所有业务表包含租户范围；版本、请求指纹和幂等键建立唯一约束；运行与节点状态、取消、重试、补偿、未知核对和时间证据可恢复。
- MySQL 5.7 实际启动验证：Flyway 成功验证 56 个迁移，当前 schema `v56`，重复启动显示无需迁移。

## 新增/扩展 API

- 资源：`GET /api/growth-workspace/resources`、`GET /resources/{id}`、`POST /resources/versions`、`POST /resources/{id}/versions/{version}/activate`
- 搜索证据：`POST /api/growth-workspace/searches`、`GET /searches`、`GET /searches/{id}`
- 工作流：`GET /workflows`、`GET /workflows/{id}`、`POST /workflows/versions`、`POST /workflows/{id}/versions/{version}/activate`、`POST /workflows/{id}/preflight`
- 运行：`POST /workflow-runs`、`GET /workflow-runs`、`GET /workflow-runs/{id}`、`POST /cancel`、`POST /retry-failed`、`POST /compensate`、`POST /nodes/{nodeId}/resolve-unknown`
- 隔离夹具：`POST /api/qa/growth-workspace/fixtures`、`POST /api/qa/growth-workspace/runs/{runId}/advance`；仅 qa profile、租户 1、账号 101/102/103，本地持久化且不访问闲鱼平台。

## 主要变更文件

- backend: `GrowthResourceService.java`、`GrowthSearchEvidenceService.java`、`GrowthWorkflowService.java`、`GrowthWorkflowScheduler.java`、`GrowthWorkspaceController.java`、`QaGrowthWorkspaceController.java`。
- permission: `AccountDataPermissionHandler.java`、`MybatisPlusConfig.java`、`AccessControlInterceptor.java`。
- frontend: `api/growth-workspace.ts`、`views/operations/index.vue`、`views/opportunities/index.vue`、`views/price-comparison/index.vue`、`views/supplies/index.vue`、`views/workflows/index.vue`。
- tests: `GrowthResourceServiceTest.java`、`WorkflowDefinitionServiceTest.java`、`AccountDataPermissionHandlerTest.java`、`AccessControlInterceptorTest.java`。

## 验证结果

- frontend type check: `scripts/local-toolchain.sh npm --prefix vue-code run type-check` — passed。
- frontend production build: `scripts/local-toolchain.sh npm --prefix vue-code run build-only` — passed，359 modules transformed。
- targeted backend: `scripts/local-toolchain.sh ./mvnw -q -Dtest=GrowthResourceServiceTest,WorkflowDefinitionServiceTest test` — passed。
- backend full suite: `scripts/local-toolchain.sh ./mvnw -q test` — 277 tests，0 failures，0 errors，0 skipped。
- package/deploy: `scripts/native-qa.sh deploy` — type check、Vite build、Maven package、原子制品切换和健康检查通过；制品 `xianyusmart-3.4.0-rc.1-20260915T134059Z-5a6ff8089216.jar`。
- migration: 启动日志显示 `Successfully validated 56 migrations`、`Current version ... 56`、`Schema ... is up to date`。
- runtime E2E:
  - `qa-wave7-happy-comp-v2` 的 run 1005 最终 `SUCCEEDED`；同 requestId 补偿重放为幂等；只补偿 COLLECT/MATERIAL，触发、搜索、筛选和发布预检为 `NOT_REQUIRED`；平台写入 0。
  - 素材 271 未提交价格，详情和列表返回 `amount=null`；素材 353 显式提交 `12.34`，详情与列表保持 `12.34`、库存 2。
  - 143 条素材按 25 条/页；161 条货源按 24 条/页；1000 次运行按 25 条/页，翻页和详情延迟加载通过。
- Product Design QA: 1920×1080、3840×2160、390×844；桌面、手机、空/加载/错误/无权限实现、大数据、任务状态、键盘名称、文本溢出和滚动通过。详见 `design-qa.md`。

## 已知安全降级

- 工作流 PUBLISH 节点只有草稿结构和能力预检，不调用真实发布适配器；真实平台写入明确关闭。
- 商机/比价只显示保存的来源证据，当前没有通用平台采集授权时不自动抓取，也不生成确定性销量或价格结论。
- 素材“生成”目前是本地资料版本化，不调用外部生成式图片服务。
- 佣金/分销因缺少可验证归因和结算来源继续隐藏。
- 真实账号 202/203 未执行任何破坏性动作；本批全部写验证均落在隔离 QA 数据。
