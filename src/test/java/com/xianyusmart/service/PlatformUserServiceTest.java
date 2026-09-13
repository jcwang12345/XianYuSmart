package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.controller.dto.PlatformUserSaveReqDTO;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.SysLoginTokenMapper;
import com.xianyusmart.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
                mock(PlatformPermissionService.class), mock(AccountAccessService.class));
        PlatformUserSaveReqDTO request = new PlatformUserSaveReqDTO();
        request.setRole(SysUser.ROLE_ADMIN);
        UserContext.set(8L, "manager", 1L);
        TenantContext.set(1L);

        BusinessException error = assertThrows(BusinessException.class, () -> service.save(request));
        assertEquals(403, error.getCode());
    }
}
