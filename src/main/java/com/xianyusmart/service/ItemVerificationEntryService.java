package com.xianyusmart.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.controller.dto.ItemVerificationEntryDTO;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.SysUserMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

/** No HTTP client, persistence, scheduler or CAPTCHA integration. */
@Service
public class ItemVerificationEntryService {
    static final String HOST = "h5api.m.goofish.com";
    static final Set<String> PATHS = Set.of("/_____tmd_____/punish",
            "/h5/mtop.taobao.idle.pc.detail/1.0/_____tmd_____/punish");
    static final long TTL_MS = 120_000;
    static final int MAX_ENTRIES = 100;
    private final SysUserMapper users;
    private final AccountAccessService access;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ItemVerificationEntryService(SysUserMapper users, AccountAccessService access,
                                        JdbcTemplate jdbc, ObjectMapper json) {
        this(users, access, jdbc, json, Clock.systemUTC());
    }
    ItemVerificationEntryService(SysUserMapper users, AccountAccessService access,
                                 JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.users = users; this.access = access; this.jdbc = jdbc; this.json = json; this.clock = clock;
    }
    private record Owner(Long userId, Long tenantId) { }
    private static final class Entry {
        final Owner owner;
        final Long account;
        final String item, url, path;
        final long expires;
        Entry(Owner owner, Long account, String item, String url, String path, long expires) {
            this.owner = owner; this.account = account; this.item = item;
            this.url = url; this.path = path; this.expires = expires;
        }
        @Override public String toString() { return "VerificationEntry[URL_OMITTED]"; }
    }
    private Owner requireOwner(Long account, String item) {
        Long userId = UserContext.getUserId(), tenant = UserContext.getTenantId();
        if (userId == null || tenant == null) throw new BusinessException(401, "请先登录");
        if (account == null || account <= 0 || item == null || !item.matches("[0-9]{1,32}"))
            throw new BusinessException(400, "账号或商品无效");
        // Select only authorization fields, never passwords, TOTP or credentials.
        SysUser user = users.selectOne(new QueryWrapper<SysUser>()
                .select("id", "tenant_id", "status", "member_role", "account_scope_mode")
                .eq("id", userId));
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())
                || !"OWNER".equals(user.getMemberRole()) || !tenant.equals(user.getTenantId())
                || !userId.equals(user.getId())) throw forbidden();
        AccountAccessService.Scope scope = access.loadScope(user);
        if (scope == null || !(scope.unrestricted() || scope.accountIds().contains(account))) throw forbidden();
        Long accountCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM xianyu_account WHERE id=? AND tenant_id=?", Long.class, account, tenant);
        Long itemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM xianyu_goods WHERE xianyu_account_id=? AND xy_good_id=?", Long.class, account, item);
        if (accountCount == null || accountCount != 1 || itemCount == null || itemCount != 1) throw forbidden();
        return new Owner(userId, tenant);
    }
    private static BusinessException forbidden() {
        return new BusinessException(403, "仅当前租户账号拥有者可使用本商品官方验证入口");
    }
    /** Only called for a real normal detail response; failure never changes detail classification. */
    public synchronized void observe(Long account, String item, String response) {
        try {
            Owner owner = requireOwner(account, item);
            prune();
            entries.values().removeIf(e -> e.owner.equals(owner) && e.account.equals(account) && e.item.equals(item));
            if (response == null || response.length() > 1_000_000) return;
            JsonNode root = json.readTree(response);
            if (!root.path("ret").isArray() || root.path("ret").isEmpty()
                    || !"FAIL_SYS_USER_VALIDATE".equals(root.path("ret").get(0).asText().split("::", 2)[0])) return;
            JsonNode value = root.path("data").path("url");
            if (!value.isTextual()) return;
            String url = value.textValue();
            String path = allowedPath(url);
            if (path == null) return;
            while (entries.size() >= MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
            entries.put(UUID.randomUUID().toString(), new Entry(owner, account, item, url, path, clock.millis() + TTL_MS));
        } catch (Exception ignored) {
            // No raw response, URL, exception message, or failed authorization is logged.
        }
    }
    public synchronized ItemVerificationEntryDTO.Metadata metadata(Long account, String item) {
        Owner owner = requireOwner(account, item);
        prune();
        for (var pair : entries.entrySet()) {
            Entry e = pair.getValue();
            if (e.owner.equals(owner) && e.account.equals(account) && e.item.equals(item))
                return new ItemVerificationEntryDTO.Metadata(true, pair.getKey(), e.expires, HOST, e.path, null);
        }
        return new ItemVerificationEntryDTO.Metadata(false, null, null, null, null,
                "当前没有可安全使用的官方验证入口；请等待有效入口，不要反复同步或扫码");
    }
    public synchronized ItemVerificationEntryDTO.Consumed consume(Long account, String item, String handle) {
        Owner owner = requireOwner(account, item); // Re-check current role, scope and ownership at consumption.
        prune();
        Entry e = handle == null ? null : entries.get(handle);
        if (e == null || !e.owner.equals(owner) || !e.account.equals(account) || !e.item.equals(item))
            throw new BusinessException(409, "验证入口已失效或不属于当前商品");
        entries.remove(handle);
        return new ItemVerificationEntryDTO.Consumed(e.url);
    }
    private void prune() {
        entries.values().removeIf(e -> e.expires <= clock.millis());
    }
    static String allowedPath(String value) {
        if (value == null || value.length() > 8192 || value.chars().anyMatch(c -> c <= 32 || c == 127)) return null;
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost()) || uri.getRawUserInfo() != null
                    || !(uri.getPort() == -1 || uri.getPort() == 443) || !PATHS.contains(uri.getRawPath())) return null;
            // Challenge tickets may be opaque. Reject URL-bearing or redirect parameters to other locations.
            String query = uri.getRawQuery();
            if (query != null) for (String part : query.split("&")) {
                String decoded = part;
                for (int i = 0; i < 4; i++) {
                    String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                    if (next.equals(decoded)) break;
                    decoded = next;
                }
                if (decoded.contains("%") || decoded.chars().anyMatch(c -> c < 32 || c == 127)) return null;
                String lowered = decoded.toLowerCase(java.util.Locale.ROOT);
                if (lowered.contains("://") || lowered.contains("//") || lowered.contains("\\")
                        || lowered.matches(".*(?:redirect|returnurl|return_url|callback|jumpurl|targeturl|next)=.*")) return null;
            }
            // A fragment cannot be needed by this static server challenge and could hide a navigation target.
            if (uri.getRawFragment() != null) return null;
            return uri.getRawPath();
        } catch (IllegalArgumentException ignored) { return null; }
    }
}
