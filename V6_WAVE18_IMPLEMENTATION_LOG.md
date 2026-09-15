# V6 Wave 18 商品发布后字段回读与审计实施记录

## 需求范围

- `PUB-03`：发布结果必须区分平台失败、结果未知、平台已确认但本地待修复，并生成商品事件与任务记录。
- `P03-01`：平台写入成功后回读，生成标题、详情、售价、库存、类目和图片字段 diff。
- `P03-07 / P03-08`：平台已返回商品 ID 后，回读失败不得自动重复发布；显示“字段核对待完成”和安全处置建议。
- `P03-11 / P03-14`：发布动作进入统一审计、商品事件和任务中心，保留账号、操作者、请求 ID、平台/本地结果。

## 实现结果

- 平台发布返回商品 ID 后最多执行 3 次只读详情回读；商品 ID 仍是写入已确认的权威证据，回读失败只影响字段核对状态，不会把成功发布误判为可重试失败。
- 回读只接受安全字段：商品 ID、标题、详情、售价、库存、类目、图片；生成 `SAME / DIFFERENT / UNAVAILABLE` 逐字段差异及汇总数量。
- 平台归一化后的可用字段写回本地主档；本地落库失败继续进入 `LOCAL_PENDING`，不会掩盖平台已成功事实。
- 任务、事件和接口统一返回 `verificationStatus`、`platformWrite`、`platformReadBack`、`fieldDifferences` 与 `recoveryHint`。
- QA 本地通道返回与真实通道同构的隔离回读证据，明确 `QA_FIXTURE / NOT_PERFORMED`，不访问闲鱼网络。
- 发布结果页展示最终标题、售价/库存、类目、图片数量和六项字段核对；手机端改为单列卡片。
- Product Design 审查发现发布成功记录在统一日志中被归类为“其他系统操作”。已将发布映射为 `PRODUCT_PUBLISH`，补齐“发布商品”筛选项与来源标签。

## 变更文件

- 后端：`PlatformPublishService.java`、`PublishQaMockService.java`、`MerchantTaskMapper.java`、`ProductEventService.java`、`MerchantOperationsService.java`。
- 前端：商品发布结果、运营任务、统一操作日志页面及商家接口类型。
- 测试：平台发布回读、QA 同构结果、任务映射、商品事件、审计操作类型与请求指纹。
- 静态资源：由最终前端源码重新生成 `src/main/resources/static`，未采用独立测试任务产生的中间构建产物。

## 数据库与 API

- 无新增迁移；MySQL 5.7 现有 Flyway 记录 `60/60` 成功。
- 现有发布执行 API 响应扩展字段，不改变原有路径；任务详情与商品事件沿用现有查询接口。
- 统一审计操作类型新增实际使用的 `PRODUCT_PUBLISH` 映射；前端可按发布商品筛选。

## 测试与运行证据

- 后端全量：`scripts/local-toolchain.sh ./mvnw -q test`，`335 tests / 0 failures / 0 errors / 0 skipped`。
- 前端类型：`../scripts/local-toolchain.sh npm run type-check`，通过。
- 最终生产构建：Vite `365 modules transformed`，通过。
- 代码检查：`git diff --check`，通过。
- 裸机 QA：`http://127.0.0.1:3000`，健康状态 `UP`；Docker 仅运行 MySQL `127.0.0.1:13306`。
- 隔离 E2E：账号 101、草稿 `QA-PUBLISH-V6-WAVE4-办公插件隔离验收`，任务 41，商品 `QA-PUBLISHED-41`；六字段一致、`QA_CONFIRMED / QA_FIXTURE / NOT_PERFORMED`、本地已同步。
- 统一审计：最新记录显示“发布商品 / PUBLISH / 任务执行成功 / admin / 成功”，不再显示“其他系统操作”；浏览器控制台 warning/error 为 0。
- 不可变制品：`.tools/native-qa/releases/xianyusmart-4.0.0-rc.1-20260915T210202Z-7e80d11597be.jar`，SHA-256 `7e80d11597beaf5c16d3c9b153d6fe18a4749960199cea6f1191ebbbf537db1a`。

## Product Design QA

- 桌面发布、预检、确认、结果证据和统一审计完整复核。
- 390×844 下结果卡片单列，六项字段差异可读，无页面级横向溢出；滚动与底部动作可达。
- 加载、预检差异、发布成功和图片加载失败状态均保留当前草稿上下文。
- Product Design 审查直接促成了发布审计分类与筛选入口修复。

## 安全边界与残余降级

- 本批只执行 Tenant 1 / 账号 101 / `QA-PUBLISH-*` / `QA_LOCAL` 隔离写入，不触达真实平台；真实账号 202/203 未写入。
- 真实平台回读尚未在用户新建测试商品上执行。若平台已返回商品 ID 但详情暂不可读，系统显示“字段核对待完成”，明确禁止再次发布；该状态是安全降级，不宣称字段已验证。
- 本批没有退款、删除、申诉或现有正式商品变更。
