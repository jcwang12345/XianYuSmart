package com.xianyusmart.service;

import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 平台能力探测；未知或无资格的功能绝不伪装为已生效。 */
@Service
public class AccountCapabilityService {

    private static final Map<String, Definition> DEFINITIONS = definitions();
    private final JdbcTemplate jdbcTemplate;
    private final AccountAccessService accountAccessService;

    public AccountCapabilityService(JdbcTemplate jdbcTemplate, AccountAccessService accountAccessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountAccessService = accountAccessService;
    }

    @Transactional
    public int probe() {
        Long tenantId = requireTenantId();
        List<Map<String, Object>> accounts = jdbcTemplate.queryForList("""
                SELECT account.id, account.status, COALESCE(cookie.cookie_status, 0) cookie_status
                FROM xianyu_account account
                LEFT JOIN xianyu_cookie cookie ON cookie.xianyu_account_id = account.id
                WHERE account.tenant_id = ?
                """ + accountCondition("account"), tenantId);
        int count = 0;
        for (Map<String, Object> account : accounts) {
            Long accountId = ((Number) account.get("id")).longValue();
            boolean credentialReady = number(account.get("status")) == 1 && number(account.get("cookie_status")) == 1;
            for (Map.Entry<String, Definition> entry : DEFINITIONS.entrySet()) {
                Definition definition = entry.getValue();
                String status = definition.localCapability
                        ? (credentialReady ? "READY" : "DEGRADED")
                        : definition.defaultStatus;
                String detail = definition.localCapability && !credentialReady
                        ? "账号连接或登录凭证不可用" : definition.detail;
                count += jdbcTemplate.update("""
                        INSERT INTO xianyu_account_capability
                            (tenant_id, xianyu_account_id, capability_code, capability_name, status, source, detail)
                        VALUES (?, ?, ?, ?, ?, 'LOCAL_PROBE', ?)
                        ON DUPLICATE KEY UPDATE capability_name = VALUES(capability_name),
                            status = IF(source = 'MANUAL', status, VALUES(status)),
                            detail = IF(source = 'MANUAL', detail, VALUES(detail)), checked_time = NOW(3)
                        """, tenantId, accountId, entry.getKey(), definition.name, status, detail);
            }
        }
        return count;
    }

    public List<Map<String, Object>> list() {
        probe();
        return jdbcTemplate.queryForList("""
                SELECT capability.id, capability.xianyu_account_id accountId,
                       account.account_note accountNote, capability.capability_code capabilityCode,
                       capability.capability_name capabilityName, capability.status,
                       capability.source, capability.detail, capability.checked_time checkedTime
                FROM xianyu_account_capability capability
                JOIN xianyu_account account ON account.id = capability.xianyu_account_id
                WHERE capability.tenant_id = ?
                """ + accountCondition("account") + " ORDER BY account.id, capability.capability_code",
                requireTenantId());
    }

    @Transactional
    public void override(Long accountId, String capabilityCode, String status, String detail) {
        accountAccessService.requireAccess(accountId);
        String normalizedCode = capabilityCode == null ? "" : capabilityCode.trim().toUpperCase();
        if (!DEFINITIONS.containsKey(normalizedCode)) throw new BusinessException(400, "能力代码无效");
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        if (!List.of("READY", "DEGRADED", "REQUIRES_PLATFORM_PERMISSION", "NOT_IMPLEMENTED", "UNKNOWN")
                .contains(normalizedStatus)) throw new BusinessException(400, "能力状态无效");
        int updated = jdbcTemplate.update("""
                UPDATE xianyu_account_capability capability
                JOIN xianyu_account account ON account.id = capability.xianyu_account_id
                SET capability.status = ?, capability.source = 'MANUAL', capability.detail = ?, capability.checked_time = NOW(3)
                WHERE capability.tenant_id = ? AND capability.xianyu_account_id = ?
                  AND capability.capability_code = ? AND account.tenant_id = ?
                """, normalizedStatus, detail, requireTenantId(), accountId, normalizedCode, requireTenantId());
        if (updated == 0) throw new BusinessException(404, "账号能力记录不存在");
    }

    private static Map<String, Definition> definitions() {
        Map<String, Definition> result = new LinkedHashMap<>();
        result.put("MESSAGING", new Definition("消息收发", true, "UNKNOWN", "依赖账号连接"));
        result.put("AI_REPLY", new Definition("AI/规则自动回复", true, "UNKNOWN", "本地能力"));
        result.put("VIRTUAL_DELIVERY", new Definition("虚拟商品自动发货", true, "UNKNOWN", "本地能力"));
        result.put("PRODUCT_PUBLISH", new Definition("商品发布", true, "UNKNOWN", "发布前仍会执行平台预检和回读确认"));
        result.put("PHYSICAL_LOGISTICS", new Definition("实物物流", false, "NOT_IMPLEMENTED", "等待账号接口能力验证"));
        result.put("REFUND_MANAGEMENT", new Definition("退款处理", false, "NOT_IMPLEMENTED", "等待账号接口能力验证"));
        result.put("FAN_PRICE", new Definition("粉丝价", false, "REQUIRES_PLATFORM_PERMISSION", "需闲鱼商家权限"));
        result.put("BARGAIN_ACTIVITY", new Definition("小刀活动", false, "REQUIRES_PLATFORM_PERMISSION", "需闲鱼商家权限"));
        result.put("COIN_DEDUCTION", new Definition("闲鱼币抵扣", false, "REQUIRES_PLATFORM_PERMISSION", "需闲鱼商家权限"));
        result.put("PAID_PROMOTION", new Definition("平台曝光推广", false, "REQUIRES_PLATFORM_PERMISSION", "需闲鱼商家/推广权限"));
        result.put("OFFICIAL_OAUTH", new Definition("官方开放平台授权", false, "REQUIRES_PLATFORM_PERMISSION", "需开放平台应用资格"));
        return Map.copyOf(result);
    }

    private String accountCondition(String alias) {
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        if (scope == null || scope.unrestricted()) return "";
        if (scope.accountIds().isEmpty()) return " AND 1 = 0";
        String ids = scope.accountIds().stream().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        return " AND " + alias + ".id IN (" + ids + ")";
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) throw new BusinessException(401, "登录状态已失效");
        return tenantId;
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private record Definition(String name, boolean localCapability, String defaultStatus, String detail) {
    }
}
