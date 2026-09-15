package com.xianyusmart.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.xianyusmart.controller.dto.PlatformUserPasswordReqDTO;
import com.xianyusmart.controller.dto.PlatformUserRespDTO;
import com.xianyusmart.controller.dto.PlatformUserSaveReqDTO;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.SysLoginToken;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.SysLoginTokenMapper;
import com.xianyusmart.mapper.SysUserMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/**
 * 全站账号与权限管理
 */
@Service
public class PlatformUserService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final SysUserMapper userMapper;
    private final SysLoginTokenMapper loginTokenMapper;
    private final PlatformPermissionService permissionService;
    private final AccountAccessService accountAccessService;
    private final OperationLogService operationLogService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final Gson gson = new Gson();

    public PlatformUserService(SysUserMapper userMapper,
                               SysLoginTokenMapper loginTokenMapper,
                               PlatformPermissionService permissionService,
                               AccountAccessService accountAccessService,
                               OperationLogService operationLogService) {
        this.userMapper = userMapper;
        this.loginTokenMapper = loginTokenMapper;
        this.permissionService = permissionService;
        this.accountAccessService = accountAccessService;
        this.operationLogService = operationLogService;
    }

    public Map<String, Object> list() {
        List<SysUser> users = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getTenantId, requireTenantId())
                .orderByDesc(SysUser::getId));
        List<PlatformUserRespDTO> records = users.stream().map(this::toResponse).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", records.size());
        result.put("activeCount", records.stream().filter(item -> Integer.valueOf(1).equals(item.getStatus())).count());
        result.put("adminCount", records.stream()
                .filter(item -> SysUser.ROLE_ADMIN.equalsIgnoreCase(item.getRole())).count());
        return result;
    }

    @Transactional
    public PlatformUserRespDTO save(PlatformUserSaveReqDTO request) {
        String requestId=requireRequestId(request==null?null:request.getRequestId());
        PlatformUserRespDTO before = request.getId() == null ? null : existingResponse(request.getId());
        assertPlatformRoleChangeAllowed(request.getId(), request.getRole());
        String role = normalizeRole(request.getRole());
        String memberRole = normalizeMemberRole(request.getMemberRole(), role);
        assertMemberRoleChangeAllowed(request.getId(), memberRole);
        String scopeMode = AccountAccessService.normalizeMode(request.getAccountScopeMode());
        int status = Integer.valueOf(0).equals(request.getStatus()) ? 0 : 1;
        List<String> permissions = normalizePermissions(request.getPermissions(), role);
        SysUser user;
        if (request.getId() == null) {
            user = createUser(request, role, memberRole, scopeMode, status);
        } else {
            user = updateUser(request.getId(), role, memberRole, scopeMode, status);
        }
        if (SysUser.ROLE_ADMIN.equals(role)) {
            permissionService.replacePermissions(user.getId(), List.of());
        } else {
            permissionService.replacePermissions(user.getId(), permissions);
        }
        accountAccessService.replaceScope(user.getId(), user.getTenantId(), scopeMode,
                request.getAccountIds(), request.getAccountGroupIds());
        PlatformUserRespDTO after = toResponse(userMapper.selectById(user.getId()));
        Map<String, Object> diff = memberDiff(before, after);
        if (before != null && !diff.isEmpty()) {
            // 让角色、权限或账号范围收窄对旧会话的下一次请求立即生效。
            revokeTokens(user.getId());
        }
        auditMemberChange(user.getId(), requestId, before, after, diff);
        return after;
    }

    @Transactional
    public void resetPassword(PlatformUserPasswordReqDTO request) {
        String requestId=requireRequestId(request==null?null:request.getRequestId());
        if (request.getUserId() == null) {
            throw new BusinessException(400, "账号ID不能为空");
        }
        validatePassword(request.getNewPassword());
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, request.getUserId())
                .eq(SysUser::getTenantId, requireTenantId()));
        if (user == null) {
            throw new BusinessException(404, "平台账号不存在");
        }
        if (SysUser.ROLE_ADMIN.equalsIgnoreCase(user.getRole()) && !isActorPlatformAdmin()) {
            throw new BusinessException(403, "租户管理员不能重置平台管理员密码");
        }
        Long activeSessions = loginTokenMapper.selectCount(new LambdaQueryWrapper<SysLoginToken>()
                .eq(SysLoginToken::getUserId, user.getId()));
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setUpdatedTime(now());
        userMapper.updateById(user);
        revokeTokens(user.getId());
        auditPasswordReset(user.getId(), requestId, activeSessions == null ? 0 : activeSessions);
    }

    public List<PermissionCatalog.PermissionOption> permissionOptions() {
        return PermissionCatalog.options();
    }

    private SysUser createUser(PlatformUserSaveReqDTO request, String role, String memberRole,
                               String scopeMode, int status) {
        String username = normalizeUsername(request.getUsername());
        validatePassword(request.getPassword());
        Long duplicate = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        if (duplicate != null && duplicate > 0) {
            throw new BusinessException(400, "用户名已存在");
        }
        SysUser user = new SysUser();
        user.setTenantId(requireTenantId());
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(role);
        user.setMemberRole(memberRole);
        user.setAccountScopeMode(scopeMode);
        user.setStatus(status);
        user.setCreatedTime(now());
        user.setUpdatedTime(now());
        userMapper.insert(user);
        return user;
    }

    private SysUser updateUser(Long userId, String role, String memberRole,
                               String scopeMode, int status) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .eq(SysUser::getTenantId, requireTenantId()));
        if (user == null) {
            throw new BusinessException(404, "平台账号不存在");
        }
        boolean removingActiveAdmin = SysUser.ROLE_ADMIN.equalsIgnoreCase(user.getRole())
                && Integer.valueOf(1).equals(user.getStatus())
                && (!SysUser.ROLE_ADMIN.equals(role) || status == 0);
        if (removingActiveAdmin) {
            // 数据库行锁覆盖事务提交阶段和多实例并发，确保全站始终保留启用管理员。
            List<Long> activeAdminIds = userMapper.lockActiveAdminIds();
            if (activeAdminIds.contains(userId) && activeAdminIds.size() <= 1) {
                throw new BusinessException(400, "至少需要保留一个启用中的管理员账号");
            }
        }
        boolean removingActiveOwner = "OWNER".equalsIgnoreCase(user.getMemberRole())
                && Integer.valueOf(1).equals(user.getStatus())
                && (!"OWNER".equalsIgnoreCase(memberRole) || status == 0);
        if (removingActiveOwner) {
            Long activeOwners = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getTenantId, requireTenantId())
                    .eq(SysUser::getMemberRole, "OWNER")
                    .eq(SysUser::getStatus, 1));
            if (activeOwners != null && activeOwners <= 1) {
                throw new BusinessException(400, "至少需要保留一个启用中的负责人");
            }
        }
        boolean identityChanged = !role.equalsIgnoreCase(user.getRole()) || status != user.getStatus()
                || !memberRole.equalsIgnoreCase(user.getMemberRole())
                || !scopeMode.equalsIgnoreCase(user.getAccountScopeMode());
        user.setRole(role);
        user.setMemberRole(memberRole);
        user.setAccountScopeMode(scopeMode);
        user.setStatus(status);
        user.setUpdatedTime(now());
        userMapper.updateById(user);
        if (identityChanged) {
            revokeTokens(userId);
        }
        return user;
    }

    private List<String> normalizePermissions(List<String> permissionCodes, String role) {
        if (SysUser.ROLE_ADMIN.equals(role)) {
            return List.of();
        }
        Set<String> allowed = PermissionCatalog.codes();
        List<String> normalized = permissionCodes == null ? List.of() : permissionCodes.stream()
                .filter(allowed::contains)
                .distinct()
                .toList();
        boolean hasMenu = normalized.stream().anyMatch(code -> code.startsWith("menu:"));
        if (!hasMenu) {
            throw new BusinessException(400, "普通用户至少需要一个菜单权限");
        }
        return normalized;
    }

    private String normalizeRole(String role) {
        if (SysUser.ROLE_ADMIN.equalsIgnoreCase(role)) {
            return SysUser.ROLE_ADMIN;
        }
        if (SysUser.ROLE_USER.equalsIgnoreCase(role)) {
            return SysUser.ROLE_USER;
        }
        throw new BusinessException(400, "账号角色无效");
    }

    private String normalizeMemberRole(String memberRole, String platformRole) {
        if (SysUser.ROLE_ADMIN.equals(platformRole)) {
            return "OWNER";
        }
        String normalized = memberRole == null ? "OPERATOR" : memberRole.trim().toUpperCase();
        if (!Set.of("OWNER", "TENANT_ADMIN", "OPERATOR", "SUPPORT", "FINANCE").contains(normalized)) {
            throw new BusinessException(400, "团队角色无效");
        }
        return normalized;
    }

    private void assertPlatformRoleChangeAllowed(Long targetUserId, String requestedRole) {
        if (isActorPlatformAdmin()) return;
        if (SysUser.ROLE_ADMIN.equalsIgnoreCase(requestedRole)) {
            throw new BusinessException(403, "租户管理员不能创建平台管理员");
        }
        if (targetUserId != null) {
            SysUser target = userMapper.selectById(targetUserId);
            if (target != null && SysUser.ROLE_ADMIN.equalsIgnoreCase(target.getRole())) {
                throw new BusinessException(403, "租户管理员不能修改平台管理员");
            }
        }
    }

    private boolean isActorPlatformAdmin() {
        SysUser actor = UserContext.getUserId() == null ? null : userMapper.selectById(UserContext.getUserId());
        return actor != null && SysUser.ROLE_ADMIN.equalsIgnoreCase(actor.getRole());
    }

    private void assertMemberRoleChangeAllowed(Long targetUserId, String requestedMemberRole) {
        if (isActorPlatformAdmin()) return;
        SysUser actor = userMapper.selectById(UserContext.getUserId());
        if (actor != null && "OWNER".equalsIgnoreCase(actor.getMemberRole())) return;
        if ("OWNER".equalsIgnoreCase(requestedMemberRole)) {
            throw new BusinessException(403, "租户管理员不能授予负责人角色");
        }
        if (targetUserId != null) {
            SysUser target = userMapper.selectById(targetUserId);
            if (target != null && "OWNER".equalsIgnoreCase(target.getMemberRole())) {
                throw new BusinessException(403, "租户管理员不能修改负责人");
            }
        }
    }

    private String normalizeUsername(String username) {
        String normalized = username == null ? "" : username.trim();
        if (normalized.length() < 3 || normalized.length() > 20) {
            throw new BusinessException(400, "用户名长度需在3-20之间");
        }
        if (!normalized.matches("[A-Za-z0-9_\\-\\u4e00-\\u9fa5]+")) {
            throw new BusinessException(400, "用户名只能包含中英文、数字、下划线或短横线");
        }
        return normalized;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 72) {
            throw new BusinessException(400, "密码长度需在8-72之间");
        }
    }

    private void revokeTokens(Long userId) {
        loginTokenMapper.delete(new LambdaQueryWrapper<SysLoginToken>()
                .eq(SysLoginToken::getUserId, userId));
    }

    private PlatformUserRespDTO existingResponse(Long userId) {
        SysUser existing = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .eq(SysUser::getTenantId, requireTenantId()));
        if (existing == null) {
            throw new BusinessException(404, "平台账号不存在");
        }
        return toResponse(existing);
    }

    private PlatformUserRespDTO toResponse(SysUser user) {
        PlatformUserRespDTO response = new PlatformUserRespDTO();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setRole(user.getRole());
        response.setTenantId(user.getTenantId());
        response.setMemberRole(user.getMemberRole());
        response.setAccountScopeMode(user.getAccountScopeMode());
        response.setAccountIds(accountAccessService.getAccountIds(user.getId()));
        response.setAccountGroupIds(accountAccessService.getGroupIds(user.getId()));
        response.setStatus(user.getStatus());
        response.setPermissions(permissionService.getPermissionCodes(user.getId()));
        response.setLastLoginTime(user.getLastLoginTime());
        response.setLastLoginIp(user.getLastLoginIp());
        response.setCreatedTime(user.getCreatedTime());
        return response;
    }

    private String now() {
        return LocalDateTime.now().format(FORMATTER);
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(401, "登录状态已失效");
        }
        return tenantId;
    }

    private String requireRequestId(String value){
        if(value==null||value.trim().isEmpty())throw new BusinessException(400,"requestId不能为空");
        String text=value.trim();if(text.length()>80)throw new BusinessException(400,"requestId不能超过80个字符");return text;
    }

    private Map<String, Object> memberSnapshot(PlatformUserRespDTO user) {
        if (user == null) return null;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("username", user.getUsername());
        snapshot.put("role", user.getRole());
        snapshot.put("memberRole", user.getMemberRole());
        snapshot.put("status", user.getStatus());
        snapshot.put("accountScopeMode", user.getAccountScopeMode());
        snapshot.put("permissions", user.getPermissions() == null ? List.of() : user.getPermissions());
        snapshot.put("accountIds", user.getAccountIds() == null ? List.of() : user.getAccountIds());
        snapshot.put("accountGroupIds", user.getAccountGroupIds() == null ? List.of() : user.getAccountGroupIds());
        return snapshot;
    }

    private Map<String, Object> memberDiff(PlatformUserRespDTO before, PlatformUserRespDTO after) {
        Map<String, Object> beforeMap = memberSnapshot(before);
        Map<String, Object> afterMap = memberSnapshot(after);
        Map<String, Object> diff = new LinkedHashMap<>();
        if (beforeMap == null) {
            afterMap.forEach((key, value) -> {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("before", "未创建");
                change.put("after", value);
                diff.put(key, change);
            });
            return diff;
        }
        afterMap.forEach((key, value) -> {
            Object old = beforeMap.get(key);
            if (!Objects.equals(old, value)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("before", old);
                change.put("after", value);
                diff.put(key, change);
            }
        });
        return diff;
    }

    private void auditMemberChange(Long targetId, String requestId, PlatformUserRespDTO before,
                                   PlatformUserRespDTO after, Map<String, Object> diff) {
        com.xianyusmart.entity.XianyuOperationLog event=new com.xianyusmart.entity.XianyuOperationLog();
        event.setOperationType("TEAM_MEMBER_SAVE");event.setOperationModule("团队与权限");
        event.setOperationDesc(before == null ? "创建团队成员并应用最小权限" : "更新团队成员权限与账号范围");
        event.setOperationStatus(1);event.setTargetType("SYS_USER");event.setTargetId(String.valueOf(targetId));
        event.setRequestId(requestId);event.setIdempotencyKey(requestId);event.setOutcomeState("LOCAL_SUCCESS");event.setDataSource("LOCAL");
        event.setRequestParams(gson.toJson(Map.of("before", memberSnapshot(before) == null ? "未创建" : memberSnapshot(before))));
        event.setResponseResult(gson.toJson(Map.of("after", memberSnapshot(after))));
        event.setFieldDiffJson(gson.toJson(diff));
        operationLogService.logRequired(event);
    }

    private void auditPasswordReset(Long targetId, String requestId, long revokedSessions) {
        com.xianyusmart.entity.XianyuOperationLog event = new com.xianyusmart.entity.XianyuOperationLog();
        event.setOperationType("TEAM_MEMBER_PASSWORD_RESET");event.setOperationModule("团队与权限");
        event.setOperationDesc("重置团队成员密码并撤销设备会话");event.setOperationStatus(1);
        event.setTargetType("SYS_USER");event.setTargetId(String.valueOf(targetId));event.setRequestId(requestId);
        event.setIdempotencyKey(requestId);event.setOutcomeState("LOCAL_SUCCESS");event.setDataSource("LOCAL");
        event.setRequestParams("{\"password\":\"已提供但不记录\"}");
        event.setResponseResult(gson.toJson(Map.of("revokedSessions", revokedSessions)));
        event.setFieldDiffJson(gson.toJson(Map.of(
                "password", Map.of("before", "已配置", "after", "已更新"),
                "activeSessions", Map.of("before", revokedSessions, "after", 0))));
        operationLogService.logRequired(event);
    }
}
