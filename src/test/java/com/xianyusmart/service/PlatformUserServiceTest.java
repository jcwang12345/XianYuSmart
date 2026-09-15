package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.controller.dto.PlatformUserSaveReqDTO;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.SysLoginTokenMapper;
import com.xianyusmart.mapper.SysUserMapper;
import com.xianyusmart.entity.XianyuOperationLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import org.mockito.ArgumentCaptor;

import java.util.List;

class PlatformUserServiceTest {

    @AfterEach
    void clearContext() {
        UserContext.clear();
        TenantContext.clear();
    }

    @Test
    void tenantAdministratorCannotPromoteAPlatformAdministrator() {
        SysUserMapper users = mock(SysUserMapper.class);
        SysUser actor = new SysUser();
        actor.setId(8L);
        actor.setRole(SysUser.ROLE_USER);
        actor.setMemberRole("TENANT_ADMIN");
        when(users.selectById(8L)).thenReturn(actor);
        PlatformUserService service = new PlatformUserService(users, mock(SysLoginTokenMapper.class),
                mock(PlatformPermissionService.class), mock(AccountAccessService.class),mock(OperationLogService.class));
        PlatformUserSaveReqDTO request = new PlatformUserSaveReqDTO();
        request.setRole(SysUser.ROLE_ADMIN);
        request.setRequestId("req-member-role");
        UserContext.set(8L, "manager", 1L);
        TenantContext.set(1L);

        BusinessException error = assertThrows(BusinessException.class, () -> service.save(request));
        assertEquals(403, error.getCode());
    }

    @Test
    void permissionChangeRevokesExistingSessionsAndWritesFieldDiff() {
        SysUserMapper users = mock(SysUserMapper.class);
        SysLoginTokenMapper tokens = mock(SysLoginTokenMapper.class);
        PlatformPermissionService permissions = mock(PlatformPermissionService.class);
        AccountAccessService accounts = mock(AccountAccessService.class);
        OperationLogService audit = mock(OperationLogService.class);
        SysUser actor = user(8L, SysUser.ROLE_ADMIN, "OWNER");
        SysUser target = user(50L, SysUser.ROLE_USER, "OPERATOR");
        target.setAccountScopeMode("SELECTED");
        when(users.selectById(anyLong())).thenAnswer(invocation ->
                Long.valueOf(8L).equals(invocation.getArgument(0)) ? actor : target);
        when(users.selectOne(any())).thenReturn(target);
        when(permissions.getPermissionCodes(50L))
                .thenReturn(List.of("menu:dashboard"), List.of("menu:dashboard", "menu:orders"));
        when(accounts.getAccountIds(50L)).thenReturn(List.of(101L));
        when(accounts.getGroupIds(50L)).thenReturn(List.of());
        PlatformUserService service = new PlatformUserService(users, tokens, permissions, accounts, audit);
        PlatformUserSaveReqDTO request = new PlatformUserSaveReqDTO();
        request.setId(50L); request.setRole(SysUser.ROLE_USER); request.setMemberRole("OPERATOR");
        request.setAccountScopeMode("SELECTED"); request.setStatus(1); request.setAccountIds(List.of(101L));
        request.setAccountGroupIds(List.of()); request.setPermissions(List.of("menu:dashboard", "menu:orders"));
        request.setRequestId("req-permission-diff");
        UserContext.set(8L, "admin", 1L); TenantContext.set(1L);

        service.save(request);

        verify(tokens).delete(any());
        ArgumentCaptor<XianyuOperationLog> log = ArgumentCaptor.forClass(XianyuOperationLog.class);
        verify(audit).logRequired(log.capture());
        assertEquals("req-permission-diff", log.getValue().getRequestId());
        org.junit.jupiter.api.Assertions.assertTrue(log.getValue().getFieldDiffJson().contains("permissions"));
        org.junit.jupiter.api.Assertions.assertFalse(log.getValue().getRequestParams().contains("password"));
    }

    private SysUser user(Long id, String role, String memberRole) {
        SysUser user = new SysUser();
        user.setId(id); user.setTenantId(1L); user.setUsername("user" + id); user.setRole(role);
        user.setMemberRole(memberRole); user.setAccountScopeMode("SELECTED"); user.setStatus(1);
        return user;
    }
}
