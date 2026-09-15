# V6 Wave 9 实施记录 — ACC-11 账号运行档案隔离证据

## 版本与范围

- requirement: `ACC-11`（每个闲鱼账号拥有稳定独立的浏览器运行档案，并能发现档案/存储状态复用冲突）。
- safety: 只读取账号 101 的隔离 QA 数据；真实账号 202/203 未修改；未执行商品、订单、消息或平台写操作。
- runtime: 裸机 macOS Java，`http://127.0.0.1:3000`；Docker MySQL 5.7，`127.0.0.1:13306`。

## 交付

- 每个账号继续使用独立 `profile_key`、浏览器上下文与加密 storage state；运行档案不按租户或店铺组共享。
- 新增 storage state 的 SHA-256 不可逆摘要，只用于识别两个账号误用同一浏览器状态；API 不返回摘要、Cookie、Token 或 storage state。
- 兼容 V57 以前的数据：首次读取档案时以解密后的真实状态校正摘要，只更新摘要列，不重写敏感内容。
- 账号矩阵详情返回稳定、脱敏的 `BPR-xxxxxxxxxxxx` 浏览器档案 ID、账号级存储范围、冲突数量、隔离状态和处理建议。
- 冲突状态分为 `ISOLATED`、`CONFLICT`、`UNKNOWN`；查询失败不得显示隔离正常。

## 数据库迁移

- `V57__browser_state_isolation_fingerprint.sql`
  - `xianyu_device_profile.storage_state_fingerprint CHAR(64)`。
  - 增加摘要检索索引；原始浏览器状态仍使用既有加密类型处理器保存。
- MySQL 5.7 升级实测：成功验证 57 个迁移，由 V56 应用 V57；第二次启动显示 current schema V57。

## 主要变更文件

- backend: `XianyuDeviceProfile.java`、`AccountBrowserProfileService.java`、`AccountMatrixService.java`。
- frontend: `api/matrix.ts`、`AccountProfileModal.vue`。
- tests: `AccountBrowserProfileServiceTest.java`、`AccountMatrixServiceTest.java`。
- build output: `src/main/resources/static`。

## 验证

- targeted backend: `scripts/local-toolchain.sh ./mvnw -q -Dtest=AccountBrowserProfileServiceTest,AccountMatrixServiceTest test` — 21 tests，0 failures/errors/skips。
- full backend: `scripts/local-toolchain.sh ./mvnw -q test` — 65 suites / 291 tests，0 failures/errors/skips。
- frontend type check / production build: `scripts/native-qa.sh deploy` — passed，359 modules transformed。
- package and health: Maven package success；裸机 3000 健康检查通过。
- final deployed artifact: `.tools/native-qa/releases/xianyusmart-4.0.0-rc.1-20260915T150938Z-5718d5140337.jar`。
- build acceleration: `scripts/native-qa.sh deploy-backend` 可在前端已经完成类型检查、生产构建和设计 QA 后复用静态资源；本次最终 Java 修正只用 7.331 秒完成 Maven 打包，避免重复生成 125 个哈希静态文件。

## Product Design 增量 QA

- 参考既有账号 360 和鱼麦多店铺详情的信息层级，仅扩展“账号运行档案”卡，不改变品牌、配色或整体布局。
- 桌面：档案 ID 改为整行等宽稳定标识；隔离状态位于卡片标题右侧；来源与隐私边界在卡片底部。
- 390×844：详情为全屏单列，卡片宽度 390px，无页面级横向溢出；档案 ID、每账号独立、平台、视口、时区和浏览器状态均可见。
- 设计审查首轮发现 ID 被窄列拆行、结论文案缺少分隔；已修正后复核。
- 状态限制：`CONFLICT` 的文案和颜色由组件及单元测试覆盖；本轮没有为截图而污染 QA 数据制造真实账号冲突。

## 已知边界

- 该能力隔离的是 XianYuSmart 自己创建的桌面 Web 浏览器上下文；不宣称模拟闲鱼手机 App 或绕过平台风控。
- 隔离状态用于发现本地档案/存储误复用，不能证明公网 IP、设备网络或平台服务端画像不同。
