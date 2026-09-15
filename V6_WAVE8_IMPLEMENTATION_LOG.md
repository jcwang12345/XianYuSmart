# V6 Wave 8 实施记录 — 系统治理、安全与诊断收口

## 版本与范围

- version: `4.0.0-rc.1`
- requirements: `SEC-02`、`SYS-01`、`SYS-02`，以及团队权限、两步验证、租户诊断和 Wave 7 独立测试缺陷 `V6-W7-QA-001`、`V6-W7-QA-002`。
- QA runtime: 裸机 macOS Java/Vue，`http://127.0.0.1:3000`。
- database: Docker MySQL 5.7，`127.0.0.1:13306`。
- safe scope: 本轮页面检查只读取现有数据；菜单与权限草稿均未保存；未执行真实商品发布、改价、上下架、删除、退款、申诉或外部消息发送。

## 需求交付

| 需求 | 交付摘要 |
|---|---|
| SEC-02 敏感配置 | AI、Embedding、图片模型和 SMTP 密钥改为加密类型处理；接口只返回 `configured` 与更新时间，不回显密钥；空值更新保留已有密钥；历史明文在应用就绪后原地加密且日志不含密钥。 |
| 团队权限 | 成员角色、岗位、状态、账号/分组范围和权限项生成字段级 before/after diff；任何实际权限变化立即撤销目标成员会话；密码重置同样撤销会话且审计不记录明文密码。 |
| 两步验证 | 绑定、确认、停用和登录校验按用户限流（10 分钟 5 次）；状态返回剩余恢复码数量；安全审计包含请求 ID，但不包含 TOTP secret、验证码或恢复码。 |
| SYS-01 菜单管理 | 展示“当前已保存 / 保存后”双栏预览、待保存状态和权限不变提示；保存携带请求 ID并产生字段差异审计。 |
| SYS-02 运维 | 新增 `V6_OPERATIONS_RUNBOOK.md`，覆盖裸机应用 + Docker MySQL、全 Docker、备份、升级、回滚、故障处理和安全边界；README 增加入口。 |
| 诊断可信度 | 修复诊断误用操作人 ID 作为租户 ID；每项显示影响、处理建议、来源和证据时间；未知状态不再显示为正常；系统提醒、业务待办和外部投递失败分开计数。 |
| V6-W7-QA-001 | 工作流运行和素材 360 档案均补齐 dialog 语义、背景隔离、初始聚焦、Tab 循环、Esc 关闭和焦点恢复。 |
| V6-W7-QA-002 | 素材详情从不可变版本历史选取 ACTIVE 版本作为“当前版本”，顶部与版本列表保持一致。 |

## 数据与迁移

- 本批无 Flyway 表结构迁移，schema 仍为 `v56`。
- `LegacySysSettingSecretEncryptionMigrator` 在 `ApplicationReadyEvent` 中只迁移四类历史明文密钥；使用条件更新避免并发覆盖，不输出密钥值。
- 实际启动日志：成功验证 56 个迁移，当前 schema `56`，无需迁移。

## 主要变更文件

- security/settings: `XianyuSysSetting.java`、`LegacySysSettingSecretEncryptionMigrator.java`、`SysSettingServiceImpl.java`、`SysSettingController.java`、相关 DTO/BO。
- RBAC/2FA: `PlatformUserService.java`、`TotpService.java`、`SecurityController.java`。
- diagnostics: `OperationsDiagnosticsService.java`、`OperationsHealthEvaluator.java`。
- frontend: `views/settings/index.vue`、`views/admin-users/index.vue`、`views/operations-health/index.vue`、`views/workflows/index.vue`、`views/operations/index.vue` 及对应 API 类型。
- tests: `SysSettingServiceImplTest.java`、`PlatformUserServiceTest.java`、`TotpServiceTest.java`、`OperationsDiagnosticsServiceTest.java`、`GrowthResourceServiceTest.java`。

## 验证结果

- backend full suite: `scripts/local-toolchain.sh ./mvnw -q test` — 285 tests，0 failures，0 errors，0 skipped。
- frontend type check: `scripts/local-toolchain.sh npm --prefix vue-code run type-check` — passed。
- frontend production build: `scripts/local-toolchain.sh npm --prefix vue-code run build-only` — passed，359 modules transformed。
- package/deploy: `scripts/native-qa.sh deploy` — 类型检查、生产构建、Maven package、原子制品切换和健康检查通过。
- deployed artifact: `.tools/native-qa/releases/xianyusmart-4.0.0-rc.1-20260915T141112Z-b21ec276a939.jar`。
- runtime: `http://127.0.0.1:3000/actuator/health` 可用；本机应用 PID 20236；MySQL health 为 healthy。
- Product Design QA: 默认桌面、390×844、640×360 和 3840×2160 已检查；详情见 `design-qa.md`。
- accessibility regression: 工作流运行 #1009 和素材 #596 均自动聚焦关闭按钮，Esc 后焦点返回原“360 档案/任务”按钮；素材当前版本和 ACTIVE 列表均为 `v1`。
- browser console: 最终检查 warning/error 均为 0。

## 已知安全降级

- 更新服务未就绪时诊断明确显示未知/提醒，不宣称自动升级能力。
- AI 总开关关闭时只允许保存配置，不宣称服务可用；连通性测试要求用户重新输入密钥，后台保存值不会回显到浏览器。
- 菜单排序只影响展示，不授予后端权限；成员权限仍由角色、动作和账号范围在服务端校验。
- 真实平台写操作不属于本轮验证范围，现有真实商品未被修改。
