# DASH-01～03 经营罗盘独立验收指引

## 版本与范围

- 目标版本：`v2.6.2`
- 隔离环境：`http://127.0.0.1:3000`
- 接口：`GET /api/business-analytics/overview`、`GET /api/business-analytics/scopes`、`POST /api/qa/business-analytics/fixtures`
- 本批无数据库迁移，继续使用 `V27__business_analytics.sql` 中的店铺/商品日指标表。
- 安全边界：经营查询为本地只读；QA 夹具仅向隔离库的商品日指标表写入 `QA_FIXTURE` 数据，不调用平台发布、改价、上下架、删除、退款或申诉接口。

## 隔离商品经营夹具

仅在 `qa` profile、`app.product-batch.qa-mock.enabled=true` 且租户/店铺/商品前缀白名单完整时注册并可用。写入还要求 `menu:dashboard` 与 `action:system-write`，并复核当前用户的店铺范围。

```http
POST /api/qa/business-analytics/fixtures
Authorization: Bearer <QA_TOKEN>
Content-Type: application/json

{
  "requestId": "qa-dashboard-acceptance-001",
  "accountId": 101,
  "size": 100,
  "metricDate": "2026-09-15"
}
```

- `requestId` 必须以 `qa-` 开头；同一请求重放会覆盖同一批日指标，不增加重复数据或重复审计。
- `size` 范围 1～100；商品必须已经存在于白名单店铺，且商品 ID 必须符合 QA 前缀。
- 返回必须包含 `safeFixture=true`、`platformNetworkCalls=false`、`source=QA_FIXTURE`、`coverageStatus=FULL`。
- 100 条场景包含商品排行压力、高曝光低点击、高咨询零支付；低库存仅根据已有 QA 商品的已同步库存判断。
- 首次成功应生成一条 `ANALYTICS_QA_FIXTURE` 统一审计；同 requestId 重放后按请求 ID 查询仍为一条。

## DASH-01 经营范围与时间

1. 全部店铺、单店、店铺分组三种范围均可查询，单店与分组参数互斥。
2. 快捷时间支持近 1/7/30 天；自定义时间包含首尾两天，开始时间不得晚于结束时间，最长 366 天。
3. 无权访问的店铺必须返回 404，不能回退到全部店铺。
4. 页面刷新和异常跳转回商品/订单页时，应保留 `accountId`；商品异常同时保留商品 ID 搜索词。
5. 只有 `menu:dashboard` 的成员可通过 `/scopes` 读取自身可见店铺和完整包含于授权范围的分组，不要求账号管理权限，也不得泄漏部分越权分组。

最小权限账号为 `designqa_admin`：`USER/OPERATOR`、仅 `menu:dashboard`、`SELECTED` 店铺 101。密码不进入仓库或文档，独立测试在本机执行以下命令读取：

```bash
security find-generic-password -a designqa_admin -s xianyusmart-native-qa-designqa -w
```

该账号 `/scopes` 只能看到店铺 101 和完全包含于该范围的分组；直接查询店铺 102 返回 403。QA 夹具写入仍请使用具备 `action:system-write` 的隔离管理员，不要扩大最小权限账号。

## DASH-02 指标、趋势与漏斗

1. 十二项核心指标显示本期值、上期值与变化；退款金额/退款率、咨询量/回复率必须是独立卡片。本期或上期未同步时显示“未同步”，不得显示为 0。
2. 上期为 0、本期大于 0 时显示“新增”，不得生成无限大或误导性百分比。
3. `activeProductCount` 口径为：逐自然日汇总范围内各店动销商品数，再取所选期间的单日峰值；单店与全部店铺结果应按该口径分别计算。
4. 趋势可切换订单、GMV、咨询、曝光；缺失点保持 `null/未同步`，不得补零。
5. 漏斗顺序为曝光→访问→咨询→支付→成交，只有分母和分子均已同步时才显示转化率。
6. 接口应返回 `source`、`coverageStatus`、`coveredAccountCount`、`requestedAccountCount`、`sampleDays`、`syncedAt`，页面可见来源和覆盖提示。

## DASH-03 排行与可执行异常

1. 店铺排行可按 GMV、订单、咨询、退款率、回复率切换，并支持最佳/待改善方向。
2. 商品排行可按支付金额、订单、曝光、点击、咨询、收藏切换，并显示来源、覆盖范围和样本天数。
3. 店铺排行和商品排行的账号、商品关联必须同时受 `tenant_id` 限制。
4. 高退款率、同步降级、高曝光低点击、高咨询零支付、低库存异常应显示证据、严重度、建议动作和安全的站内目标路由。
5. 高曝光低点击仅在点击数真实已同步时判断；高咨询零支付仅在支付订单数真实已同步时判断。未知值不得以 0 参与异常规则。
6. 异常跳转只能接受以单个 `/` 开头的站内地址，拒绝 `//` 或外部地址。

## 响应与大数据状态

1. 桌面 1920×1080、窄屏约 768px、手机约 390px、4K 宽屏均不得横向溢出；筛选和核心动作保持可见。
2. 验收加载骨架、无数据、部分同步、接口错误与无权限状态。
3. 排行与异常列表由后端限制为最多 100 条；窄屏以纵向卡片阅读，不依赖横向表格。

## 推荐命令

```bash
scripts/local-toolchain.sh ./mvnw -Dtest=BusinessAnalyticsServiceTest,QaBusinessAnalyticsControllerTest test
scripts/local-toolchain.sh npm --prefix vue-code run type-check
scripts/local-toolchain.sh npm --prefix vue-code run build-only
scripts/native-qa.sh deploy
```

3000 的 Java 应用运行在 macOS 裸机，MySQL 5.7 通过 Docker 复用隔离 QA 数据卷；完整运行方式见 `NATIVE_QA_RUNBOOK.md`。
