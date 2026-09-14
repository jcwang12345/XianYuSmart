# IM-01～06 集成客服独立验收指南

目标版本：`v2.7.1`
运行环境：macOS 裸机 Java/Vue `http://127.0.0.1:3000`，Docker 仅运行隔离 MySQL 5.7 `127.0.0.1:13306`
硬验收基线：`IM_01_06_ACCEPTANCE.md`

## 1. 安全边界

- 只使用 Tenant-A、账号 `101/102/103` 和 `qa-` requestId。
- `/api/qa/message-workspace/**` 仅在 `qa` profile、`PRODUCT_BATCH_QA_MOCK_ENABLED=true`、租户/店铺/`QA-` 商品前缀白名单同时满足时注册并可写。
- 夹具只写本地 MySQL：`platformNetworkCalls=false`、`aiNetworkCalls=false`；不会调用闲鱼、AI、企业微信或邮件。
- 不使用 `/api/message-workspace/send/**` 向真实买家发送测试消息；真实平台发送适配不属于隔离自动验收范围。
- QA 动态数据允许认领、解决、忽略和重启验证；不得修改生产 2000 端口及其数据卷。

## 2. 运行与健康检查

```bash
scripts/native-qa.sh deploy
scripts/native-qa.sh status
curl -fsS http://127.0.0.1:3000/actuator/health
```

测试管理员为 `admin`；密码继续使用隔离 QA 环境已有凭据，不写入仓库、报告或命令历史。受限角色至少覆盖：无消息菜单、只有消息菜单、消息发送动作和系统 QA 写入动作四类。

## 3. QA 夹具 API

```http
POST /api/qa/message-workspace/fixtures
Authorization: Bearer <QA_TOKEN>
Content-Type: application/json

{
  "accountId": 101,
  "scenario": "LOW_CONFIDENCE",
  "requestId": "qa-im-low-confidence-001"
}
```

支持场景：

| scenario | 预期 reasonCode | 验收重点 |
|---|---|---|
| `HUMAN_REQUEST` | `BUYER_REQUESTED_HUMAN` | 买家要求人工，不生成自动回复 |
| `SENSITIVE` | `SENSITIVE_OR_HIGH_RISK` | 退款/争议/账号安全高风险 |
| `NO_STRATEGY` | `NO_REPLY_STRATEGY` | 规则与 AI 均无可用策略 |
| `LOW_CONFIDENCE` | `LOW_CONFIDENCE` | 显示 12% 和 `qa-model`，不得伪造高置信 |
| `AI_UNAVAILABLE` | `AI_UNAVAILABLE` | AI 不可用，不向买家发送内部错误 |
| `OUTCOME_UNKNOWN` | `MESSAGE_OUTCOME_UNKNOWN` | 紧急、先核对、禁止盲重发 |

响应必须同时包含：

```json
{
  "safeFixture": true,
  "platformNetworkCalls": false,
  "aiNetworkCalls": false
}
```

同一个 `requestId` 重放时必须返回相同任务，`handoffTask.idempotentReplay=true`，且 `xianyu_ai_handoff_task` 与 `AI_HANDOFF_OPEN` 审计各只有一条。

## 4. 真实业务 API

- `GET /api/message-workspace/conversations?status=ALL&accountId=101&unreadOnly=true&pinnedOnly=true&keywordFlag=RISK&search=QA&limit=500`
- `GET /api/message-workspace/conversation?accountId=101&sessionId=<SID>&limit=100&offset=0`
- `GET /api/message-workspace/handoffs?status=ALL&accountId=101&search=QA&limit=500`
- `POST /api/message-workspace/handoffs/{id}/claim`，body：`{"requestId":"qa-im-claim-001"}`
- `POST /api/message-workspace/handoffs/{id}/resolve`，body：`{"status":"RESOLVED","note":"QA-处理说明","requestId":"qa-im-resolve-001"}`
- 忽略使用 `status=IGNORED` 且 `note` 必填；空说明必须返回 400。

认领使用 `status='OPEN'` 条件更新；并发第二人必须收到 409。所有列表与动作都要验证 Tenant-B、无店铺范围和无动作权限不可访问，且不泄露记录总数。

## 5. 持久化与重启

1. 创建 `OUTCOME_UNKNOWN` 与 `LOW_CONFIDENCE` 夹具。
2. 认领其中一条，记录任务 ID、认领人和请求 ID。
3. 执行 `scripts/native-qa.sh restart`。
4. 再查 `handoffs?status=ALL`：状态、认领人、置信度、模型、原因、时间和会话关联不得丢失。
5. 解决后会话 `handoffStatus=NONE`；若同一会话仍有另一条 OPEN/CLAIMED 任务，则保持 `HUMAN_REQUIRED`。

## 6. 前端设计 QA

- 桌面 1920×1080/1440×900：会话、正文、上下文三栏；消息正文独立滚动。
- 窄屏与 390×844：点击“会话资料”后商品、自动回复、会话运营、买家和订单入口全部可达；页面滚动不回弹。
- AI 待接管：筛选、搜索、紧急提示、置信度/模型空值、认领、解决、忽略、旧数据保留与重试。
- 买家会话：未读、置顶、标记、关键词/商品/订单搜索使用服务端结果；无权限不显示“0 个店铺”，而是明确权限原因。
- 覆盖空、加载、接口失败、部分通知源失败、无权限、100 会话、500 条长会话、XSS 文本转义和键盘焦点。
- 截图不能证明完整无障碍；仍需键盘、焦点、语义和对比度工具检查。

## 7. 商品知识版本（IM-04）

- `POST /ai/saveFixedMaterial`：创建不可变的草稿或立即启用版本，必须携带 `qa-` requestId。
- `POST /ai/getFixedMaterial`：返回当前有效版本、有效时间和版本历史；无有效版本显示 `NO_EFFECTIVE_VERSION`，不把旧的 `fixed_material` 当成当前知识。
- `POST /ai/fixedMaterial/activate`、`POST /ai/fixedMaterial/expire`：启用与停用必须幂等，精确重放只写一条事件和审计，异载荷复用 requestId 返回 409。
- 自动回复运行时只读取 `ACTIVE` 且命中生效/失效时间窗口的版本；回复记录保存 `knowledgeVersionId/knowledgeVersionNo`，便于事后还原。
- 扩展语义资料仍需 AI/Embedding；未配置时必须显示可执行的配置原因，不影响上方本地知识版本。
- 隔离 E2E 只使用账号 `101` 与 `QA-GOODS-0999`；创建草稿、重放、启用、重放、停用后，最终必须回到“当前无有效版本”，不得触发真实 AI 或平台网络请求。

## 8. 开发门禁命令

```bash
scripts/local-toolchain.sh ./mvnw test
scripts/local-toolchain.sh ./mvnw -DskipTests package
cd vue-code && ../scripts/local-toolchain.sh npm run type-check
cd vue-code && ../scripts/local-toolchain.sh npm run build-only
scripts/native-qa.sh logs 240
```

MySQL 5.7 升级日志必须显示：验证 45 个迁移、已有 v2.7.0 环境从 V43 顺序应用 V44/V45、schema 到 V45。最终静态资源必须由本提交的 `vue-code` 源码重新生成。

## 9. 已知安全降级

- 商品卡消息没有可靠平台适配，界面不提供假发送入口。
- 平台历史与买家头像依赖账号 Cookie；Cookie 不可用时保留本地消息并明确同步失败，不把本地 14 条解释为完整平台历史。
- QA 夹具不验证真实 AI 模型质量或闲鱼回执，只验证本地决策、状态机、权限、审计和恢复路径。
