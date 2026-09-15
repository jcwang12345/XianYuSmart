# V6 Wave 6 实施记录 — 客服、买家与回复策略

## 版本与范围

- version: `3.3.0-rc.1`
- requirements: `V6-IM-01`～`V6-IM-04`、`V6-BUY-01`～`V6-BUY-02`、`V6-AI-01`～`V6-AI-06`
- QA runtime: 裸机 macOS Java/Vue，`http://127.0.0.1:3000`
- database: Docker MySQL 5.7，`127.0.0.1:13306`
- safe accounts: 101/102/103 可运行隔离夹具；真实账号 202/203（外部账号 10599065/39175043）仅做读取与连通性观察。
- global safety gate: QA profile 默认 `QA_PLATFORM_WRITES_ENABLED=false`；策略测试固定平台写入 0、AI 调用 0、买家发送 0。

## 需求交付

| 需求 | 交付摘要 |
|---|---|
| V6-IM-01 | 买家会话、通知、AI 待接管共享账号范围；桌面三栏；手机列表→会话→资料；500 条工作台读取与状态提示。 |
| V6-IM-02 | 发送请求指纹、幂等键、attempt token、event ID、平台回执；UNKNOWN 禁止重发；人工核对预检/执行、证据、审计和幂等。 |
| V6-IM-03 | 首次回复 claim 绑定账号/商品/买家和源消息；发送前失败释放，发送后不确定进入 REVIEW_REQUIRED；卖家延时回复停止；SLA 待办。 |
| V6-IM-04 | 人工请求、敏感、低置信、无策略、AI 不可用、结果未知均进入幂等接管任务。 |
| V6-BUY-01 | 会话/订单轮询创建或回填买家投影；修复订单 buyerId；客服深链可打开对应买家 360。 |
| V6-BUY-02 | 买家 360 互链会话、订单、商品、评价、备注、标签、黑名单与自动化状态。 |
| V6-AI-01 | 决策优先级：黑名单/接管→敏感→关键词→有效知识→AI→转人工，并保存逐步 trace。 |
| V6-AI-02 | 关键词与内容按完整快照创建不可变版本，含 request fingerprint、生效/失效窗口和历史审计。 |
| V6-AI-03 | 回复证据保存规则、内容、知识版本、安全结论、模型、请求 ID、结果与转人工原因。 |
| V6-AI-04 | 价格、有效期、售后、安装、二维码等事实型问题没有有效知识时禁止猜测并转人工。 |
| V6-AI-05 | 无发送策略测试台展示策略、版本、安全判定、正式链路行为和完整决策 trace。 |
| V6-AI-06 | 关键词优先级、精确/包含/正则、启停、有效期、fallback、文字/图片版本、历史与模拟命中。 |

## 数据库迁移

- `V54__message_delivery_receipt_and_resolution.sql`
  - 扩展消息发送尝试、首次回复 claim、关键词规则/内容和自动回复证据。
  - 新增不可变关键词规则版本、无发送策略演练记录。
- `V55__backfill_message_attempt_evidence.sql`
  - 为 V54 前的发送尝试回填本地唯一 LEGACY token/event ID，并将两列收紧为 NOT NULL。
- MySQL 5.7 实际启动验证：V54、V55 均成功，当前 schema version `v55`。

## 新增/扩展 API

- `GET /api/message-workspace/send-attempts/{requestId}`
- `POST /api/message-workspace/send-attempts/{requestId}/resolution/preview`
- `POST /api/message-workspace/send-attempts/{requestId}/resolution`
- `PUT /api/keyword-reply/rules/{ruleId}`
- `GET /api/keyword-reply/rules/{ruleId}/versions`
- `POST /api/reply-policy/simulate`
- `POST /api/qa/message-workspace/fixtures`（qa profile、白名单、无外网）

## 主要变更文件

- backend: `MessageWorkspaceService.java`、`AiHandoffService.java`、`KeywordRuleVersionService.java`、`ReplyPolicySimulationService.java`、`ReplyFactSafetyPolicy.java`、`AutoReplyServiceImpl.java`、`KeywordReplyServiceImpl.java`、`PendingOrderPollService.java`。
- controllers/security: `MessageWorkspaceController.java`、`KeywordReplyController.java`、`ReplyPolicyController.java`、`QaMessageWorkspaceController.java`、`AccessControlInterceptor.java`。
- frontend: `views/messages/workspace.vue`、`views/auto-reply/index.vue`、`views/auto-reply/useAutoReply.ts`、`views/auto-reply/auto-reply.css`、`components/ReplyEnhancements.vue`、`api/message.ts`、`api/keywordReply.ts`、`api/reply-policy.ts`。
- tests: `MessageWorkspaceServiceTest.java`、`KeywordRuleVersionServiceTest.java`、`ReplyPolicySimulationServiceTest.java`、`ReplyFactSafetyPolicyTest.java`、`ReplyEnhancementServiceTest.java`、`KeywordReplySharingTest.java`、`AIReplyStrategyTest.java`。

## 验证结果

- frontend type check: `npm --prefix vue-code run type-check` — passed。
- frontend production build: `npm --prefix vue-code run build-only` — passed，358 modules transformed。
- backend full suite: `JAVA_HOME=.tools/jdk21/Contents/Home ./mvnw -q test` — 266 tests, 0 failures, 0 errors, 0 skipped。
- package: `./mvnw -DskipTests package` — BUILD SUCCESS。
- migration/runtime: `scripts/native-qa.sh deploy` — native QA healthy on port 3000；MySQL healthy on 13306；schema v55。
- Product Design QA: 1920 × 1080 与 390 × 844；桌面、窄屏、加载、空态、无权限、大数据量、风险状态、滚轮和长表单通过。详见 `design-qa.md`。

## 已知安全降级

- QA 环境禁止真实闲鱼平台写入；发布、回复、发货、改价、上下架等破坏性行为不在真实账号上验证。
- 策略测试不调用真实 AI；它验证确定性策略、安全门禁和证据链。
- 平台未同步或失效的商品图片明确展示不可用，不伪装成 0 或成功。
- 真实账号 202/203 仅验证连接、会话/订单读取和“自身消息不触发自动回复”；不自动发送任何内容。
