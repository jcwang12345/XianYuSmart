package com.xianyusmart.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.XianyuAccountMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 账号矩阵聚合读模型。平台事实、同步覆盖度和本地处理状态始终分层返回。
 */
@Service
public class AccountMatrixService {

    private static final Set<String> SOURCES = Set.of("PLATFORM_API", "PLATFORM_WEB", "MANUAL_IMPORT");
    private static final Set<String> SYNC_STATUSES = Set.of("SUCCEEDED", "PARTIAL", "FAILED");
    private static final Set<String> COVERAGE_STATUSES = Set.of("FULL", "PARTIAL", "UNSYNCED");
    private static final Set<String> CONNECTION_STATUSES = Set.of("CONNECTED", "DEGRADED", "EXPIRED", "DISCONNECTED", "UNKNOWN");
    private static final Set<String> AUTH_STATUSES = Set.of("AUTHORIZED", "EXPIRED", "REVOKED", "NOT_APPLICABLE", "UNKNOWN");
    private static final Set<String> RISK_SEVERITIES = Set.of("INFO", "WARNING", "HIGH", "CRITICAL");
    private static final Set<String> RISK_STATUSES = Set.of("ACTIVE", "RESOLVED", "EXPIRED", "UNKNOWN");
    private static final Set<String> APPEAL_STATUSES = Set.of("NOT_AVAILABLE", "AVAILABLE", "SUBMITTED", "ACCEPTED", "REJECTED", "EXPIRED", "UNKNOWN");
    private static final Set<String> HANDLING_STATUSES = Set.of("UNHANDLED", "ACKNOWLEDGED", "IN_PROGRESS", "DONE", "IGNORED");

    private final XianyuAccountMapper accountMapper;
    private final JdbcTemplate jdbcTemplate;
    private final AccountAccessService accountAccessService;
    private final OperationLogService operationLogService;
    private final NotificationCenterService notificationCenterService;
    private final ObjectMapper objectMapper;

    public AccountMatrixService(XianyuAccountMapper accountMapper,
                                JdbcTemplate jdbcTemplate,
                                AccountAccessService accountAccessService,
                                OperationLogService operationLogService,
                                NotificationCenterService notificationCenterService,
                                ObjectMapper objectMapper) {
        this.accountMapper = accountMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.accountAccessService = accountAccessService;
        this.operationLogService = operationLogService;
        this.notificationCenterService = notificationCenterService;
        this.objectMapper = objectMapper;
    }

    public MatrixPage listAccounts(String search, String connectionStatus, String riskSeverity,
                                   Integer page, Integer pageSize) {
        return listAccounts(search, connectionStatus, riskSeverity, null, page, pageSize);
    }

    public MatrixPage listAccounts(String search, String connectionStatus, String riskSeverity,
                                   Long groupId, Integer page, Integer pageSize) {
        int normalizedPage = page == null || page < 1 ? 1 : page;
        int normalizedSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        String normalizedSearch = trimToNull(search);
        String normalizedConnection = normalizeOptional(connectionStatus, CONNECTION_STATUSES, "连接状态");
        String normalizedSeverity = normalizeOptional(riskSeverity, RISK_SEVERITIES, "风险等级");

        List<XianyuAccount> accounts = accountMapper.selectList(new LambdaQueryWrapper<XianyuAccount>()
                .orderByAsc(XianyuAccount::getId));
        List<Map<String, Object>> matched = new ArrayList<>();
        for (XianyuAccount account : accounts) {
            Map<String, Object> view = buildAccountView(account, false);
            if (normalizedSearch != null) {
                String haystack = (string(account.getId()) + " " + string(account.getAccountNote()) + " "
                        + string(account.getUnb()) + " " + string(view.get("shopNickname"))).toLowerCase(Locale.ROOT);
                if (!haystack.contains(normalizedSearch.toLowerCase(Locale.ROOT))) continue;
            }
            if (normalizedConnection != null && !normalizedConnection.equals(view.get("connectionStatus"))) continue;
            if (normalizedSeverity != null && !normalizedSeverity.equals(view.get("highestRiskSeverity"))) continue;
            if (groupId != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> groups = (List<Map<String, Object>>) view.get("groups");
                if (groups.stream().noneMatch(group -> groupId.equals(group.get("id")))) continue;
            }
            matched.add(view);
        }
        int from = Math.min((normalizedPage - 1) * normalizedSize, matched.size());
        int to = Math.min(from + normalizedSize, matched.size());
        return new MatrixPage(List.copyOf(matched.subList(from, to)), matched.size(), normalizedPage,
                normalizedSize, (int) Math.ceil((double) matched.size() / normalizedSize));
    }

    public Map<String, Object> summary() {
        List<XianyuAccount> accounts = accountMapper.selectList(new LambdaQueryWrapper<XianyuAccount>()
                .orderByAsc(XianyuAccount::getId));
        int connected = 0;
        int attention = 0;
        int unsyncedProfiles = 0;
        int knownRiskAccounts = 0;
        int activeRisks = 0;
        for (XianyuAccount account : accounts) {
            Map<String, Object> view = buildAccountView(account, false);
            if ("CONNECTED".equals(view.get("connectionStatus"))) connected++;
            if ("HIGH".equals(view.get("highestRiskSeverity"))
                    || "CRITICAL".equals(view.get("highestRiskSeverity"))) attention++;
            if ("UNSYNCED".equals(view.get("profileCoverageStatus"))) unsyncedProfiles++;
            if (!"UNSYNCED".equals(view.get("riskCoverageStatus"))) {
                knownRiskAccounts++;
                Object count = view.get("knownActiveRiskCount");
                if (count instanceof Number number) activeRisks += number.intValue();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountCount", accounts.size());
        result.put("connectedCount", connected);
        result.put("attentionAccountCount", attention);
        result.put("unsyncedProfileCount", unsyncedProfiles);
        result.put("knownRiskAccountCount", knownRiskAccounts);
        result.put("knownActiveRiskCount", knownRiskAccounts == 0 ? null : activeRisks);
        result.put("riskCoverage", accounts.isEmpty() || knownRiskAccounts == 0
                ? "UNSYNCED"
                : knownRiskAccounts == accounts.size() ? "FULL" : "PARTIAL");
        result.put("generatedAt", Instant.now());
        return result;
    }

    public Map<String, Object> accountDetail(Long accountId) {
        XianyuAccount account = requireAccount(accountId);
        Map<String, Object> result = buildAccountView(account, true);
        result.put("accessChannels", accessChannels(accountId));
        result.put("risks", risks(accountId, null));
        result.put("datasetEvidence", Map.of(
                "shopProfile", datasetStateWithSnapshotFallback(accountId, "SHOP_PROFILE",
                        result.get("profileSource"), result.get("profileSyncStatus"),
                        result.get("profileCoverageStatus"), result.get("profileSyncedAt")),
                "shopRisks", datasetStateWithSnapshotFallback(accountId, "SHOP_RISKS",
                        result.get("riskSource"), result.get("riskSyncStatus"),
                        result.get("riskCoverageStatus"), result.get("riskSyncedAt"))));
        return result;
    }

    public List<Map<String, Object>> risks(Long accountId, String status) {
        requireAccount(accountId);
        String normalizedStatus = normalizeOptional(status, RISK_STATUSES, "风险状态");
        Long tenantId = requireTenant();
        String sql = "SELECT * FROM xianyu_shop_risk_event WHERE tenant_id = ? AND xianyu_account_id = ?"
                + (normalizedStatus == null ? "" : " AND risk_status = ?")
                + " ORDER BY FIELD(severity, 'CRITICAL','HIGH','WARNING','INFO') ASC, appeal_deadline ASC, id DESC";
        Object[] args = normalizedStatus == null
                ? new Object[]{tenantId, accountId}
                : new Object[]{tenantId, accountId, normalizedStatus};
        return jdbcTemplate.query(sql, (rs, rowNum) -> riskRow(rs), args);
    }

    public String exportRisksCsv(Long accountId) {
        XianyuAccount account = requireAccount(accountId);
        List<Map<String, Object>> risks = risks(accountId, null);
        StringBuilder csv = new StringBuilder("\uFEFF账号ID,账号备注,风险名称,等级,平台状态,本地处理状态,影响,申诉截止,来源,覆盖度,同步时间\r\n");
        for (Map<String, Object> risk : risks) {
            csv.append(csv(accountId)).append(',').append(csv(account.getAccountNote())).append(',')
                    .append(csv(risk.get("riskName"))).append(',').append(csv(risk.get("severity"))).append(',')
                    .append(csv(risk.get("riskStatus"))).append(',').append(csv(risk.get("localHandlingStatus"))).append(',')
                    .append(csv(risk.get("impactSummary"))).append(',').append(csv(risk.get("appealDeadline"))).append(',')
                    .append(csv(risk.get("source"))).append(',').append(csv(risk.get("coverageStatus"))).append(',')
                    .append(csv(risk.get("syncedAt"))).append("\r\n");
        }
        return csv.toString();
    }

    @Transactional
    public Map<String, Object> saveProfileSnapshot(Long accountId, ProfileSnapshotInput input) {
        requireAccount(accountId);
        validateProfile(input);
        Long tenantId = requireTenant();
        List<Long> existing = jdbcTemplate.queryForList(
                "SELECT id FROM xianyu_shop_profile_snapshot WHERE tenant_id = ? AND request_id = ?",
                Long.class, tenantId, input.requestId());
        if (!existing.isEmpty()) {
            Map<String, Object> replay = latestProfile(accountId);
            replay.put("idempotentReplay", true);
            return replay;
        }
        Instant syncedAt = input.syncedAt() == null ? Instant.now() : input.syncedAt();
        jdbcTemplate.update("""
                INSERT INTO xianyu_shop_profile_snapshot
                (tenant_id, xianyu_account_id, source, sync_status, coverage_status, request_id,
                 shop_nickname, avatar_url, shop_home_url, region, shop_level, shop_score,
                 super_seller, seller_credit, buyer_credit, sesame_verified, real_name_verified,
                 xianyu_expert, user_type, xianyu_upgraded, taobao_bound, pin_limit, on_sale_count,
                 sold_count, follower_count, positive_rate, total_review_count, total_bought_count,
                 total_sold_count, last_active_time, synced_at, raw_snapshot_json, error_code, error_message)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, tenantId, accountId, upper(input.source()), upper(input.syncStatus()), upper(input.coverageStatus()),
                input.requestId(), input.shopNickname(), input.avatarUrl(), input.shopHomeUrl(), input.region(),
                input.shopLevel(), input.shopScore(), bool(input.superSeller()), input.sellerCredit(), input.buyerCredit(),
                bool(input.sesameVerified()), bool(input.realNameVerified()), bool(input.xianyuExpert()), input.userType(),
                bool(input.xianyuUpgraded()), bool(input.taobaoBound()), input.pinLimit(), input.onSaleCount(), input.soldCount(),
                input.followerCount(), input.positiveRate(), input.totalReviewCount(), input.totalBoughtCount(),
                input.totalSoldCount(), timestamp(input.lastActiveTime()), timestamp(syncedAt), input.rawSnapshotJson(),
                input.errorCode(), input.errorMessage());
        upsertDatasetState(accountId, "SHOP_PROFILE", input.source(), input.syncStatus(), input.coverageStatus(),
                syncedAt, input.requestId(), input.errorCode(), input.errorMessage());
        audit(accountId, "PROFILE_SNAPSHOT", "账号资产", "保存店铺画像快照", 1,
                "SHOP_PROFILE", string(accountId), input.requestId(), input.requestId(), "PLATFORM_CONFIRMED",
                input.source(), input, null);
        Map<String, Object> result = latestProfile(accountId);
        result.put("idempotentReplay", false);
        return result;
    }

    @Transactional
    public Map<String, Object> upsertRisk(Long accountId, RiskEventInput input) {
        XianyuAccount account = requireAccount(accountId);
        validateRisk(input);
        Long tenantId = requireTenant();
        Instant syncedAt = input.syncedAt() == null ? Instant.now() : input.syncedAt();
        Map<String, Object> before = findRiskByDedupeOptional(accountId, input.dedupeKey());
        jdbcTemplate.update("""
                INSERT INTO xianyu_shop_risk_event
                (tenant_id, xianyu_account_id, dedupe_key, platform_penalty_id, rule_code, risk_name,
                 severity, impact_summary, risk_status, effective_time, penalty_time, appeal_deadline,
                 appeal_status, recommended_action, operation_advice, source, coverage_status,
                 synced_at, request_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE platform_penalty_id=VALUES(platform_penalty_id), rule_code=VALUES(rule_code),
                 risk_name=VALUES(risk_name), severity=VALUES(severity), impact_summary=VALUES(impact_summary),
                 risk_status=VALUES(risk_status), effective_time=VALUES(effective_time), penalty_time=VALUES(penalty_time),
                 appeal_deadline=VALUES(appeal_deadline), appeal_status=VALUES(appeal_status),
                 recommended_action=VALUES(recommended_action), operation_advice=VALUES(operation_advice),
                 source=VALUES(source), coverage_status=VALUES(coverage_status), synced_at=VALUES(synced_at),
                 request_id=VALUES(request_id)
                """, tenantId, accountId, input.dedupeKey(), input.platformPenaltyId(), input.ruleCode(), input.riskName(),
                upper(input.severity()), input.impactSummary(), upper(input.riskStatus()), timestamp(input.effectiveTime()),
                timestamp(input.penaltyTime()), timestamp(input.appealDeadline()), upper(input.appealStatus()),
                input.recommendedAction(), input.operationAdvice(), upper(input.source()), upper(input.coverageStatus()),
                timestamp(syncedAt), input.requestId());
        upsertDatasetState(accountId, "SHOP_RISKS", input.source(), "SUCCEEDED", input.coverageStatus(),
                syncedAt, input.requestId(), null, null);
        audit(accountId, "RISK_SYNC", "账号风险", "同步风险事件", 1, "SHOP_RISK", input.dedupeKey(),
                input.requestId(), input.requestId(), "PLATFORM_CONFIRMED", input.source(), input, null);
        Map<String, Object> result = findRiskByDedupe(accountId, input.dedupeKey());
        if ("ACTIVE".equals(result.get("riskStatus")) && riskChanged(before, result)) {
            Map<String, Object> notificationData = new LinkedHashMap<>();
            notificationData.put("riskId", result.get("riskId"));
            notificationData.put("requestId", input.requestId());
            notificationData.put("targetRoute", "/accounts?accountId=" + accountId + "&view=risks");
            notificationData.put("dedupeKey", "account:" + accountId + ":risk:" + input.dedupeKey() + ":" + input.requestId());
            notificationCenterService.dispatch("PENALTY_CREATED", accountId,
                    "店铺风险需要处理 · " + displayName(account),
                    string(result.get("riskName")) + "\n影响：" + string(result.get("impactSummary"))
                            + "\n建议：" + string(result.get("recommendedAction")),
                    notificationData);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> updateRiskHandling(Long riskId, RiskHandlingInput input) {
        if (input == null || trimToNull(input.requestId()) == null) throw new BusinessException(400, "requestId不能为空");
        String toStatus = requireEnum(input.toStatus(), HANDLING_STATUSES, "本地处理状态");
        Long tenantId = requireTenant();
        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT * FROM xianyu_shop_risk_event WHERE tenant_id = ? AND id = ?",
                (rs, rowNum) -> riskRow(rs), tenantId, riskId);
        if (rows.isEmpty()) throw new BusinessException(404, "风险事件不存在");
        Map<String, Object> risk = rows.get(0);
        Long accountId = ((Number) risk.get("accountId")).longValue();
        accountAccessService.requireAccess(accountId);
        Integer replayCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM xianyu_shop_risk_action WHERE tenant_id = ? AND request_id = ?",
                Integer.class, tenantId, input.requestId());
        if (replayCount != null && replayCount > 0) {
            risk.put("idempotentReplay", true);
            return risk;
        }
        String fromStatus = string(risk.get("localHandlingStatus"));
        jdbcTemplate.update("""
                UPDATE xianyu_shop_risk_event
                   SET local_handling_status=?, local_handling_note=?, handled_by=?, handled_at=NOW(3)
                 WHERE tenant_id=? AND id=? AND xianyu_account_id=?
                """, toStatus, trimToNull(input.note()), UserContext.getUserId(), tenantId, riskId, accountId);
        jdbcTemplate.update("""
                INSERT INTO xianyu_shop_risk_action
                (tenant_id, risk_event_id, xianyu_account_id, action_type, from_status, to_status,
                 note, operator_user_id, operator_username, request_id)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, tenantId, riskId, accountId, "LOCAL_HANDLING", fromStatus, toStatus, trimToNull(input.note()),
                UserContext.getUserId(), UserContext.getUsername(), input.requestId());
        audit(accountId, "RISK_HANDLING", "账号风险", "更新风险本地处理状态", 1, "SHOP_RISK",
                string(riskId), input.requestId(), input.requestId(), "LOCAL_SUCCESS", "LOCAL",
                input, Map.of("localHandlingStatus", Map.of("from", fromStatus, "to", toStatus)));
        Map<String, Object> updated = findRiskById(accountId, riskId);
        updated.put("idempotentReplay", false);
        return updated;
    }

    @Transactional
    public Map<String, Object> upsertAccessChannel(Long accountId, String channelCode, AccessChannelInput input) {
        XianyuAccount account = requireAccount(accountId);
        if (input == null) throw new BusinessException(400, "接入通道参数不能为空");
        requireText(input.requestId(), "requestId", 80);
        String normalizedCode = upper(channelCode);
        if (normalizedCode == null || !normalizedCode.matches("[A-Z0-9_]{2,40}")) {
            throw new BusinessException(400, "channelCode格式无效");
        }
        String connection = requireEnum(input.connectionStatus(), CONNECTION_STATUSES, "连接状态");
        String authorization = requireEnum(input.authorizationStatus(), AUTH_STATUSES, "授权状态");
        String coverage = requireEnum(input.coverageStatus(), COVERAGE_STATUSES, "覆盖状态");
        String source = requireEnum(input.source(), Set.of("LOCAL", "PLATFORM_API", "PLATFORM_WEB", "SYSTEM_DERIVED"), "来源");
        Long tenantId = requireTenant();
        Map<String, Object> before = accessChannels(accountId).stream()
                .filter(item -> normalizedCode.equals(item.get("channelCode"))).findFirst().orElse(Map.of());
        jdbcTemplate.update("""
                INSERT INTO xianyu_account_access_channel
                (tenant_id, xianyu_account_id, channel_code, channel_name, connection_status,
                 authorization_status, authorization_scope, credential_expire_time, capabilities_json,
                 source, coverage_status, last_checked_time, last_success_time, last_error_code, last_error_message)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE channel_name=VALUES(channel_name), connection_status=VALUES(connection_status),
                 authorization_status=VALUES(authorization_status), authorization_scope=VALUES(authorization_scope),
                 credential_expire_time=VALUES(credential_expire_time), capabilities_json=VALUES(capabilities_json),
                 source=VALUES(source), coverage_status=VALUES(coverage_status), last_checked_time=VALUES(last_checked_time),
                 last_success_time=VALUES(last_success_time), last_error_code=VALUES(last_error_code),
                 last_error_message=VALUES(last_error_message)
                """, tenantId, accountId, normalizedCode, requireText(input.channelName(), "通道名称", 100),
                connection, authorization, trimToNull(input.authorizationScope()), timestamp(input.credentialExpireTime()),
                json(input.capabilities()), source, coverage, Timestamp.from(Instant.now()), timestamp(input.lastSuccessTime()),
                trimToNull(input.lastErrorCode()), trimToNull(input.lastErrorMessage()));
        Map<String, Object> after = accessChannels(accountId).stream()
                .filter(item -> normalizedCode.equals(item.get("channelCode"))).findFirst().orElseThrow();
        audit(accountId, "ACCESS_CHANNEL_UPDATE", "账号接入", "更新账号接入通道状态", 1,
                "ACCESS_CHANNEL", normalizedCode, input.requestId(), input.requestId(), "LOCAL_SUCCESS", source, input,
                Map.of("before", before, "after", after));
        if (accessStateChanged(before, after)) {
            notifyAccessTransition(account, normalizedCode, input.requestId(), before, after);
        }
        return after;
    }

    private Map<String, Object> buildAccountView(XianyuAccount account, boolean includeProfile) {
        Map<String, Object> profile = latestProfile(account.getId());
        List<Map<String, Object>> channels = accessChannels(account.getId());
        Map<String, Object> primaryChannel = channels.stream()
                .filter(item -> "CONNECTED".equals(item.get("connectionStatus"))).findFirst()
                .orElse(channels.isEmpty() ? null : channels.get(0));
        Map<String, Object> riskState = datasetState(account.getId(), "SHOP_RISKS");
        int knownRiskCount = countActiveRisks(account.getId());
        String riskCoverage = string(riskState.get("coverageStatus"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountId", account.getId());
        result.put("accountNote", account.getAccountNote());
        result.put("unb", account.getUnb());
        result.put("accountStatus", account.getStatus());
        result.put("connectionStatus", primaryChannel == null ? "UNKNOWN" : primaryChannel.get("connectionStatus"));
        result.put("authorizationStatus", primaryChannel == null ? "UNKNOWN" : primaryChannel.get("authorizationStatus"));
        result.put("credentialExpireTime", primaryChannel == null ? null : primaryChannel.get("credentialExpireTime"));
        result.put("shopNickname", profile.get("shopNickname"));
        result.put("shopLevel", profile.get("shopLevel"));
        result.put("profileSource", profile.get("source"));
        result.put("profileSyncStatus", profile.get("syncStatus"));
        result.put("profileCoverageStatus", profile.get("coverageStatus"));
        result.put("profileSyncedAt", profile.get("syncedAt"));
        result.put("riskSource", riskState.get("source"));
        result.put("riskSyncStatus", riskState.get("syncStatus"));
        result.put("riskCoverageStatus", riskCoverage);
        result.put("riskSyncedAt", riskState.get("asOfTime"));
        result.put("knownActiveRiskCount", knownRiskCount > 0 || "FULL".equals(riskCoverage) ? knownRiskCount : null);
        result.put("highestRiskSeverity", highestRiskSeverity(account.getId()));
        result.put("groups", accountGroups(account.getId()));
        Map<String, Object> runtime = runtimeProfile(account.getId());
        result.put("runtimeProfileStatus", runtime.get("status"));
        result.put("runtimeProfileType", runtime.get("profileType"));
        result.put("runtimePlatform", runtime.get("platform"));
        result.put("runtimeViewport", runtime.get("viewport"));
        result.put("browserStateReady", runtime.get("browserStateReady"));
        result.put("runtimeProfile", runtime);
        if (includeProfile) result.put("profile", profile);
        return result;
    }

    private Map<String, Object> runtimeProfile(Long accountId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT profile_key profileKey,profile_type profileType,platform,locale,timezone_id timezoneId,
                       viewport_width viewportWidth,viewport_height viewportHeight,device_scale_factor deviceScaleFactor,
                       color_scheme colorScheme,browser_version browserVersion,
                       CASE WHEN browser_storage_state IS NULL OR browser_storage_state='' THEN 0 ELSE 1 END browserStateReady,
                       storage_state_updated_time storageStateUpdatedTime,status,created_time createdTime,updated_time updatedTime
                  FROM xianyu_device_profile WHERE tenant_id=? AND xianyu_account_id=? LIMIT 1
                """, requireTenant(), accountId);
        if (rows.isEmpty()) return Map.of("status", "UNSYNCED", "browserStateReady", false);
        Map<String, Object> runtime = new LinkedHashMap<>(rows.get(0));
        runtime.put("viewport", runtime.get("viewportWidth") + "x" + runtime.get("viewportHeight"));
        Object storedStatus = runtime.get("status");
        runtime.put("status", storedStatus instanceof Number number && number.intValue() == 1 ? "ACTIVE" : "DISABLED");
        return runtime;
    }

    private void notifyAccessTransition(XianyuAccount account, String channelCode, String requestId,
                                        Map<String, Object> before, Map<String, Object> after) {
        Long accountId = account.getId();
        String connection = string(after.get("connectionStatus"));
        String authorization = string(after.get("authorizationStatus"));
        String beforeConnection = string(before.get("connectionStatus"));
        String beforeAuthorization = string(before.get("authorizationStatus"));
        String eventType;
        String title;
        String nextStep;
        if ("CONNECTED".equals(connection) && "AUTHORIZED".equals(authorization)
                && !("CONNECTED".equals(beforeConnection) && "AUTHORIZED".equals(beforeAuthorization))) {
            eventType = "ACCOUNT_RECOVERED";
            title = "账号连接已恢复";
            nextStep = "系统将按安全重试规则恢复任务；结果未知的动作仍需人工核对。";
        } else if ("EXPIRED".equals(connection) || "EXPIRED".equals(authorization)) {
            eventType = "CREDENTIAL_EXPIRED";
            title = "账号凭证已过期";
            nextStep = "请使用该账号的闲鱼 App 扫码续期。";
        } else if (Set.of("DISCONNECTED", "DEGRADED").contains(connection)
                || Set.of("REVOKED", "UNKNOWN").contains(authorization)) {
            eventType = "ACCOUNT_OFFLINE";
            title = "账号连接需要处理";
            nextStep = "请检查连接详情；不要重复执行结果未知的平台动作。";
        } else {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", requestId);
        data.put("channelCode", channelCode);
        data.put("connectionStatus", connection);
        data.put("authorizationStatus", authorization);
        data.put("targetRoute", "/connection/" + accountId);
        data.put("dedupeKey", "account:" + accountId + ":access:" + channelCode + ":" + requestId);
        notificationCenterService.dispatch(eventType, accountId, title + " · " + displayName(account),
                "连接：" + connection + "；授权：" + authorization + "。\n影响：消息、订单同步与自动化能力可能变化。\n下一步：" + nextStep,
                data);
    }

    private boolean riskChanged(Map<String, Object> before, Map<String, Object> after) {
        if (before == null || before.isEmpty()) return true;
        return !java.util.Objects.equals(before.get("riskStatus"), after.get("riskStatus"))
                || !java.util.Objects.equals(before.get("severity"), after.get("severity"))
                || !java.util.Objects.equals(before.get("impactSummary"), after.get("impactSummary"));
    }

    private boolean accessStateChanged(Map<String, Object> before, Map<String, Object> after) {
        return !java.util.Objects.equals(before.get("connectionStatus"), after.get("connectionStatus"))
                || !java.util.Objects.equals(before.get("authorizationStatus"), after.get("authorizationStatus"));
    }

    private String displayName(XianyuAccount account) {
        String note = trimToNull(account.getAccountNote());
        return note == null ? "账号 " + account.getId() : note + "（ID " + account.getId() + "）";
    }

    private List<Map<String, Object>> accountGroups(Long accountId) {
        return jdbcTemplate.queryForList("""
                SELECT groups.id,groups.group_name groupName,groups.color
                  FROM xianyu_account_group groups
                  JOIN xianyu_account_group_member member
                    ON member.tenant_id=groups.tenant_id AND member.group_id=groups.id
                 WHERE groups.tenant_id=? AND member.xianyu_account_id=?
                 ORDER BY groups.sort_order,groups.id
                """, requireTenant(), accountId);
    }

    private XianyuAccount requireAccount(Long accountId) {
        if (accountId == null || accountId <= 0) throw new BusinessException(400, "账号ID无效");
        accountAccessService.requireAccess(accountId);
        XianyuAccount account = accountMapper.selectById(accountId);
        if (account == null) throw new BusinessException(404, "账号不存在或不属于当前经营主体");
        return account;
    }

    private List<Map<String, Object>> accessChannels(Long accountId) {
        return jdbcTemplate.query("""
                SELECT channel_code, channel_name, connection_status, authorization_status,
                       authorization_scope, credential_expire_time, capabilities_json, source,
                       coverage_status, last_checked_time, last_success_time, last_error_code, last_error_message
                  FROM xianyu_account_access_channel
                 WHERE tenant_id=? AND xianyu_account_id=? ORDER BY id
                """, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("channelCode", rs.getString("channel_code"));
            item.put("channelName", rs.getString("channel_name"));
            item.put("connectionStatus", rs.getString("connection_status"));
            item.put("authorizationStatus", rs.getString("authorization_status"));
            item.put("authorizationScope", rs.getString("authorization_scope"));
            item.put("credentialExpireTime", instant(rs, "credential_expire_time"));
            item.put("capabilities", readJson(rs.getString("capabilities_json")));
            item.put("source", rs.getString("source"));
            item.put("coverageStatus", rs.getString("coverage_status"));
            item.put("lastCheckedTime", instant(rs, "last_checked_time"));
            item.put("lastSuccessTime", instant(rs, "last_success_time"));
            item.put("lastErrorCode", rs.getString("last_error_code"));
            item.put("lastErrorMessage", rs.getString("last_error_message"));
            return item;
        }, requireTenant(), accountId);
    }

    private Map<String, Object> latestProfile(Long accountId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT * FROM xianyu_shop_profile_snapshot
                 WHERE tenant_id=? AND xianyu_account_id=? ORDER BY created_time DESC, id DESC LIMIT 1
                """, (rs, rowNum) -> profileRow(rs), requireTenant(), accountId);
        if (!rows.isEmpty()) return rows.get(0);
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("source", "NONE");
        unknown.put("syncStatus", "UNSYNCED");
        unknown.put("coverageStatus", "UNSYNCED");
        unknown.put("syncedAt", null);
        for (String key : List.of("shopNickname", "avatarUrl", "shopHomeUrl", "region", "shopLevel", "shopScore",
                "superSeller", "sellerCredit", "buyerCredit", "sesameVerified", "realNameVerified", "xianyuExpert",
                "userType", "xianyuUpgraded", "taobaoBound", "pinLimit", "onSaleCount", "soldCount", "followerCount",
                "positiveRate", "totalReviewCount", "totalBoughtCount", "totalSoldCount", "lastActiveTime", "errorCode",
                "errorMessage")) unknown.put(key, null);
        return unknown;
    }

    private Map<String, Object> profileRow(ResultSet rs) throws SQLException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("snapshotId", rs.getLong("id"));
        map.put("source", rs.getString("source"));
        map.put("syncStatus", rs.getString("sync_status"));
        map.put("coverageStatus", rs.getString("coverage_status"));
        map.put("requestId", rs.getString("request_id"));
        for (String[] field : new String[][]{{"shopNickname","shop_nickname"},{"avatarUrl","avatar_url"},
                {"shopHomeUrl","shop_home_url"},{"region","region"},{"shopLevel","shop_level"},
                {"sellerCredit","seller_credit"},{"buyerCredit","buyer_credit"},{"userType","user_type"},
                {"errorCode","error_code"},{"errorMessage","error_message"}}) map.put(field[0], rs.getString(field[1]));
        map.put("shopScore", rs.getBigDecimal("shop_score"));
        for (String[] field : new String[][]{{"superSeller","super_seller"},{"sesameVerified","sesame_verified"},
                {"realNameVerified","real_name_verified"},{"xianyuExpert","xianyu_expert"},
                {"xianyuUpgraded","xianyu_upgraded"},{"taobaoBound","taobao_bound"}}) {
            map.put(field[0], nullableBoolean(rs, field[1]));
        }
        for (String[] field : new String[][]{{"pinLimit","pin_limit"},{"onSaleCount","on_sale_count"},{"soldCount","sold_count"}}) {
            map.put(field[0], nullableInteger(rs, field[1]));
        }
        for (String[] field : new String[][]{{"followerCount","follower_count"},{"totalReviewCount","total_review_count"},
                {"totalBoughtCount","total_bought_count"},{"totalSoldCount","total_sold_count"}}) {
            map.put(field[0], nullableLong(rs, field[1]));
        }
        map.put("positiveRate", rs.getBigDecimal("positive_rate"));
        map.put("lastActiveTime", instant(rs, "last_active_time"));
        map.put("syncedAt", instant(rs, "synced_at"));
        return map;
    }

    private Map<String, Object> riskRow(ResultSet rs) throws SQLException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("riskId", rs.getLong("id"));
        map.put("accountId", rs.getLong("xianyu_account_id"));
        map.put("dedupeKey", rs.getString("dedupe_key"));
        map.put("platformPenaltyId", rs.getString("platform_penalty_id"));
        map.put("ruleCode", rs.getString("rule_code"));
        map.put("riskName", rs.getString("risk_name"));
        map.put("severity", rs.getString("severity"));
        map.put("impactSummary", rs.getString("impact_summary"));
        map.put("riskStatus", rs.getString("risk_status"));
        map.put("effectiveTime", instant(rs, "effective_time"));
        map.put("penaltyTime", instant(rs, "penalty_time"));
        map.put("appealDeadline", instant(rs, "appeal_deadline"));
        map.put("appealStatus", rs.getString("appeal_status"));
        map.put("recommendedAction", rs.getString("recommended_action"));
        map.put("operationAdvice", rs.getString("operation_advice"));
        map.put("source", rs.getString("source"));
        map.put("coverageStatus", rs.getString("coverage_status"));
        map.put("syncedAt", instant(rs, "synced_at"));
        map.put("localHandlingStatus", rs.getString("local_handling_status"));
        map.put("localHandlingNote", rs.getString("local_handling_note"));
        map.put("handledBy", nullableLong(rs, "handled_by"));
        map.put("handledAt", instant(rs, "handled_at"));
        return map;
    }

    private Map<String, Object> datasetState(Long accountId, String datasetCode) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT source, sync_status, coverage_status, as_of_time, last_attempt_time,
                       last_success_time, last_error_code, last_error_message, request_id
                  FROM xianyu_account_dataset_state
                 WHERE tenant_id=? AND xianyu_account_id=? AND dataset_code=?
                """, (rs, rowNum) -> {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("source", rs.getString("source"));
            state.put("syncStatus", rs.getString("sync_status"));
            state.put("coverageStatus", rs.getString("coverage_status"));
            state.put("asOfTime", instant(rs, "as_of_time"));
            state.put("lastAttemptTime", instant(rs, "last_attempt_time"));
            state.put("lastSuccessTime", instant(rs, "last_success_time"));
            state.put("lastErrorCode", rs.getString("last_error_code"));
            state.put("lastErrorMessage", rs.getString("last_error_message"));
            state.put("requestId", rs.getString("request_id"));
            return state;
        }, requireTenant(), accountId, datasetCode);
        if (!rows.isEmpty()) return rows.get(0);
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("source", "NONE");
        unknown.put("syncStatus", "UNSYNCED");
        unknown.put("coverageStatus", "UNSYNCED");
        unknown.put("asOfTime", null);
        unknown.put("lastAttemptTime", null);
        unknown.put("lastSuccessTime", null);
        unknown.put("lastErrorCode", null);
        unknown.put("lastErrorMessage", null);
        unknown.put("requestId", null);
        return unknown;
    }

    private Map<String, Object> datasetStateWithSnapshotFallback(Long accountId, String datasetCode,
                                                                  Object source, Object syncStatus,
                                                                  Object coverageStatus, Object syncedAt) {
        Map<String, Object> state = datasetState(accountId, datasetCode);
        if (!"UNSYNCED".equals(state.get("coverageStatus")) || "UNSYNCED".equals(coverageStatus)) {
            return state;
        }
        // Older synchronized snapshots predate xianyu_account_dataset_state. The snapshot itself is valid
        // evidence, so expose it as a derived read model instead of contradicting the visible business data.
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("source", source == null ? "NONE" : source);
        fallback.put("syncStatus", syncStatus == null ? "UNSYNCED" : syncStatus);
        fallback.put("coverageStatus", coverageStatus);
        fallback.put("asOfTime", syncedAt);
        fallback.put("lastAttemptTime", syncedAt);
        fallback.put("lastSuccessTime", syncedAt);
        fallback.put("lastErrorCode", null);
        fallback.put("lastErrorMessage", null);
        fallback.put("requestId", null);
        fallback.put("evidenceMode", "SNAPSHOT_DERIVED");
        return fallback;
    }

    private int countActiveRisks(Long accountId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM xianyu_shop_risk_event
                 WHERE tenant_id=? AND xianyu_account_id=? AND risk_status='ACTIVE'
                """, Integer.class, requireTenant(), accountId);
        return count == null ? 0 : count;
    }

    private String highestRiskSeverity(Long accountId) {
        List<String> levels = jdbcTemplate.queryForList("""
                SELECT severity FROM xianyu_shop_risk_event
                 WHERE tenant_id=? AND xianyu_account_id=? AND risk_status='ACTIVE'
                 ORDER BY FIELD(severity, 'CRITICAL','HIGH','WARNING','INFO') ASC LIMIT 1
                """, String.class, requireTenant(), accountId);
        return levels.isEmpty() ? null : levels.get(0);
    }

    private Map<String, Object> findRiskByDedupe(Long accountId, String dedupeKey) {
        List<Map<String, Object>> rows = findRiskRowsByDedupe(accountId, dedupeKey);
        if (rows.isEmpty()) throw new BusinessException(500, "风险事件保存后无法读取");
        return rows.get(0);
    }

    private Map<String, Object> findRiskByDedupeOptional(Long accountId, String dedupeKey) {
        List<Map<String, Object>> rows = findRiskRowsByDedupe(accountId, dedupeKey);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private List<Map<String, Object>> findRiskRowsByDedupe(Long accountId, String dedupeKey) {
        return jdbcTemplate.query(
                "SELECT * FROM xianyu_shop_risk_event WHERE tenant_id=? AND xianyu_account_id=? AND dedupe_key=?",
                (rs, rowNum) -> riskRow(rs), requireTenant(), accountId, dedupeKey);
    }

    private Map<String, Object> findRiskById(Long accountId, Long riskId) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT * FROM xianyu_shop_risk_event WHERE tenant_id=? AND xianyu_account_id=? AND id=?",
                (rs, rowNum) -> riskRow(rs), requireTenant(), accountId, riskId);
        if (rows.isEmpty()) throw new BusinessException(404, "风险事件不存在");
        return rows.get(0);
    }

    private void upsertDatasetState(Long accountId, String datasetCode, String source, String syncStatus,
                                    String coverageStatus, Instant asOf, String requestId,
                                    String errorCode, String errorMessage) {
        jdbcTemplate.update("""
                INSERT INTO xianyu_account_dataset_state
                (tenant_id, xianyu_account_id, dataset_code, source, sync_status, coverage_status,
                 as_of_time, last_attempt_time, last_success_time, last_error_code, last_error_message, request_id)
                VALUES (?,?,?,?,?,?,?,NOW(3),?,?,?,?)
                ON DUPLICATE KEY UPDATE source=VALUES(source), sync_status=VALUES(sync_status),
                 coverage_status=VALUES(coverage_status), as_of_time=VALUES(as_of_time),
                 last_attempt_time=VALUES(last_attempt_time),
                 last_success_time=COALESCE(VALUES(last_success_time), last_success_time),
                 last_error_code=VALUES(last_error_code), last_error_message=VALUES(last_error_message),
                 request_id=VALUES(request_id)
                """, requireTenant(), accountId, datasetCode, upper(source), upper(syncStatus), upper(coverageStatus),
                timestamp(asOf), "FAILED".equals(upper(syncStatus)) ? null : timestamp(asOf),
                trimToNull(errorCode), trimToNull(errorMessage), requestId);
    }

    private void validateProfile(ProfileSnapshotInput input) {
        if (input == null) throw new BusinessException(400, "店铺画像参数不能为空");
        requireText(input.requestId(), "requestId", 80);
        requireEnum(input.source(), SOURCES, "来源");
        requireEnum(input.syncStatus(), SYNC_STATUSES, "同步状态");
        requireEnum(input.coverageStatus(), COVERAGE_STATUSES, "覆盖状态");
        nonNegative(input.pinLimit(), "可发布数量");
        nonNegative(input.onSaleCount(), "在售数量");
        nonNegative(input.soldCount(), "已售数量");
        nonNegative(input.followerCount(), "粉丝数量");
        if (input.positiveRate() != null
                && (input.positiveRate().compareTo(BigDecimal.ZERO) < 0 || input.positiveRate().compareTo(BigDecimal.ONE) > 0)) {
            throw new BusinessException(400, "好评率必须在0到1之间");
        }
        if (input.rawSnapshotJson() != null && input.rawSnapshotJson().length() > 1_000_000) {
            throw new BusinessException(400, "原始快照不能超过1MB");
        }
    }

    private void validateRisk(RiskEventInput input) {
        if (input == null) throw new BusinessException(400, "风险事件参数不能为空");
        requireText(input.requestId(), "requestId", 80);
        requireText(input.dedupeKey(), "dedupeKey", 255);
        requireText(input.riskName(), "风险名称", 255);
        requireEnum(input.source(), SOURCES, "来源");
        requireEnum(input.coverageStatus(), COVERAGE_STATUSES, "覆盖状态");
        requireEnum(input.severity(), RISK_SEVERITIES, "风险等级");
        requireEnum(input.riskStatus(), RISK_STATUSES, "风险状态");
        requireEnum(input.appealStatus(), APPEAL_STATUSES, "申诉状态");
    }

    private void audit(Long accountId, String type, String module, String description, int status,
                       String targetType, String targetId, String requestId, String idempotencyKey,
                       String outcome, String source, Object request, Object diff) {
        XianyuOperationLog log = new XianyuOperationLog();
        log.setXianyuAccountId(accountId);
        log.setOperationType(type);
        log.setOperationModule(module);
        log.setOperationDesc(description);
        log.setOperationStatus(status);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setRequestId(requestId);
        log.setIdempotencyKey(idempotencyKey);
        log.setOutcomeState(outcome);
        log.setDataSource(upper(source));
        log.setRequestParams(json(request));
        log.setFieldDiffJson(json(diff));
        operationLogService.log(log);
    }

    private Long requireTenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId <= 0) throw new BusinessException(401, "缺少经营主体上下文");
        return tenantId;
    }

    private String json(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(400, "JSON数据格式无效", e);
        }
    }

    private Object readJson(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (JsonProcessingException ignored) {
            return value;
        }
    }

    private String normalizeOptional(String value, Set<String> allowed, String label) {
        if (trimToNull(value) == null) return null;
        return requireEnum(value, allowed, label);
    }

    private String requireEnum(String value, Set<String> allowed, String label) {
        String normalized = upper(value);
        if (normalized == null || !allowed.contains(normalized)) {
            throw new BusinessException(400, label + "无效，可选值：" + String.join(",", allowed));
        }
        return normalized;
    }

    private String requireText(String value, String label, int maxLength) {
        String normalized = trimToNull(value);
        if (normalized == null) throw new BusinessException(400, label + "不能为空");
        if (normalized.length() > maxLength) throw new BusinessException(400, label + "不能超过" + maxLength + "个字符");
        return normalized;
    }

    private void nonNegative(Number value, String label) {
        if (value != null && value.longValue() < 0) throw new BusinessException(400, label + "不能小于0");
    }

    private static String upper(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Integer bool(Boolean value) {
        return value == null ? null : value ? 1 : 0;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value != 0;
    }

    private static String csv(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    public record MatrixPage(List<Map<String, Object>> records, int total, int page, int pageSize, int totalPages) {}

    public record ProfileSnapshotInput(
            String requestId, String source, String syncStatus, String coverageStatus,
            String shopNickname, String avatarUrl, String shopHomeUrl, String region, String shopLevel,
            BigDecimal shopScore, Boolean superSeller, String sellerCredit, String buyerCredit,
            Boolean sesameVerified, Boolean realNameVerified, Boolean xianyuExpert, String userType,
            Boolean xianyuUpgraded, Boolean taobaoBound, Integer pinLimit, Integer onSaleCount,
            Integer soldCount, Long followerCount, BigDecimal positiveRate, Long totalReviewCount,
            Long totalBoughtCount, Long totalSoldCount, Instant lastActiveTime, Instant syncedAt,
            String rawSnapshotJson, String errorCode, String errorMessage) {}

    public record RiskEventInput(
            String requestId, String dedupeKey, String platformPenaltyId, String ruleCode, String riskName,
            String severity, String impactSummary, String riskStatus, Instant effectiveTime, Instant penaltyTime,
            Instant appealDeadline, String appealStatus, String recommendedAction, String operationAdvice,
            String source, String coverageStatus, Instant syncedAt) {}

    public record RiskHandlingInput(String requestId, String toStatus, String note) {}

    public record AccessChannelInput(
            String requestId, String channelName, String connectionStatus, String authorizationStatus,
            String authorizationScope, Instant credentialExpireTime, Map<String, Object> capabilities,
            String source, String coverageStatus, Instant lastSuccessTime, String lastErrorCode,
            String lastErrorMessage) {}
}
