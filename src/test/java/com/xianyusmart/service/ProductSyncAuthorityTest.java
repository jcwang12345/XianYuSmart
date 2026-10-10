package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.*;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.SysUserMapper;
import com.xianyusmart.service.impl.ItemServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProductSyncAuthorityTest {
    private ProductSyncTaskService tasks;private SysUser user;private SysUserMapper users;
    private PlatformPermissionService permissions;private AccountAccessService access;
    @BeforeEach void setup(){
        TenantContext.set(7L);users=mock(SysUserMapper.class);permissions=mock(PlatformPermissionService.class);access=mock(AccountAccessService.class);
        user=new SysUser();user.setId(42L);user.setTenantId(7L);user.setStatus(1);user.setRole(SysUser.ROLE_USER);
        when(users.selectById(42L)).thenReturn(user);when(permissions.hasPermission(42L,PermissionCatalog.ACTION_GOODS_WRITE)).thenReturn(true);
        when(access.loadScope(user)).thenReturn(new AccountAccessService.Scope(true,Set.of()));
        tasks=new ProductSyncTaskService(mock(JdbcTemplate.class),new ObjectMapper(),access,mock(AccountAutomationPolicy.class),mock(ItemServiceImpl.class),mock(GoodsInfoService.class),mock(ItemDetailSyncService.class),mock(ProductSyncStateService.class),mock(OperationLogService.class),mock(ProductSyncQaFacts.class),mock(ItemDetailSnapshotService.class),users,permissions,mock(PlatformTransactionManager.class));
    }
    @AfterEach void clear(){tasks.close();TenantContext.clear();UserContext.clear();AccountScopeContext.clear();}
    private void check(AccountScopeContext.Scope original){ReflectionTestUtils.invokeMethod(tasks,"requireCurrentAuthority",1L,42L,7L,original);}
    @Test void currentScopeRevocationIsDetectedAgainAtSnapshotCommitBoundary(){
        var original=new AccountScopeContext.Scope(true,Set.of());check(original);
        when(access.loadScope(user)).thenReturn(new AccountAccessService.Scope(false,Set.of(2L)));
        assertEquals(403,assertThrows(BusinessException.class,()->ProductSyncCommitGuard.withAuthority(()->check(original),()->{ProductSyncCommitGuard.requireAuthority();fail("revoked snapshot cannot be committed");return null;})).getCode());
        // The thread-local callback must not leak into unrelated work after rejection.
        assertDoesNotThrow(ProductSyncCommitGuard::requireAuthority);
    }
    @Test void laterExpansionNeverBroadensOriginalScope(){
        assertEquals(403,assertThrows(BusinessException.class,()->check(new AccountScopeContext.Scope(false,Set.of(2L)))).getCode());
    }
    @Test void bothOriginalAndCurrentSelectedMustContainAccount(){
        when(access.loadScope(user)).thenReturn(new AccountAccessService.Scope(false,Set.of(1L,3L)));
        check(new AccountScopeContext.Scope(false,Set.of(1L,2L)));assertEquals(Set.of(1L),AccountScopeContext.get().accountIds());
    }
    @Test void disabledDeletedTenantChangedAndPermissionRevokedOperatorsAreRejected(){
        var original=new AccountScopeContext.Scope(true,Set.of());user.setStatus(0);
        assertEquals(403,assertThrows(BusinessException.class,()->check(original)).getCode());user.setStatus(1);user.setTenantId(8L);
        assertEquals(403,assertThrows(BusinessException.class,()->check(original)).getCode());user.setTenantId(7L);when(permissions.hasPermission(42L,PermissionCatalog.ACTION_GOODS_WRITE)).thenReturn(false);
        assertEquals(403,assertThrows(BusinessException.class,()->check(original)).getCode());when(users.selectById(42L)).thenReturn(null);
        assertEquals(403,assertThrows(BusinessException.class,()->check(original)).getCode());
    }
}
