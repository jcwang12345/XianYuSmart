package com.xianyusmart.interceptor;

import com.xianyusmart.entity.SysUser;
import com.xianyusmart.service.PermissionCatalog;
import com.xianyusmart.service.PlatformPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccessControlInterceptorTest {

    private PlatformPermissionService permissionService;
    private AccessControlInterceptor interceptor;

    @BeforeEach
    void setUp() {
        permissionService = mock(PlatformPermissionService.class);
        interceptor = new AccessControlInterceptor(permissionService);
    }

    @Test
    void accountMatrixReadOnlyEndpointDoesNotRequireWritePermission() throws Exception {
        SysUser user = user("OPERATOR");
        when(permissionService.getPermissionCodeSet(user)).thenReturn(Set.of(PermissionCatalog.MENU_ACCOUNTS));
        MockHttpServletRequest request = request("GET", "/api/account-matrix/accounts", user);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void dashboardScopeEndpointNeedsOnlyDashboardMenu() throws Exception {
        SysUser user = user("OPERATOR");
        when(permissionService.getPermissionCodeSet(user)).thenReturn(Set.of(PermissionCatalog.MENU_DASHBOARD));

        assertTrue(interceptor.preHandle(request("GET", "/api/business-analytics/scopes", user),
                new MockHttpServletResponse(), new Object()));

        when(permissionService.getPermissionCodeSet(user)).thenReturn(Set.of(PermissionCatalog.MENU_ACCOUNTS));
        MockHttpServletResponse denied = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("GET", "/api/business-analytics/scopes", user),
                denied, new Object()));
        assertEquals(403, denied.getStatus());
    }

    @Test
    void riskHandlingRequiresItsOwnPermission() throws Exception {
        SysUser user = user("OPERATOR");
        when(permissionService.getPermissionCodeSet(user)).thenReturn(Set.of(PermissionCatalog.MENU_ACCOUNTS));
        MockHttpServletRequest request = request("POST", "/api/account-matrix/risks/3/handling", user);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void accountDeleteIsSeparatedFromGeneralAccountWrite() throws Exception {
        SysUser user = user("OPERATOR");
        when(permissionService.getPermissionCodeSet(user)).thenReturn(Set.of(
                PermissionCatalog.MENU_ACCOUNTS, PermissionCatalog.ACTION_ACCOUNT_WRITE));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request("POST", "/api/account/delete", user), response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void tenantManagerCanReadMembersButNeedsPermissionToChangeThem() throws Exception {
        SysUser owner = user("OWNER");
        when(permissionService.getPermissionCodeSet(owner)).thenReturn(Set.of());

        assertTrue(interceptor.preHandle(request("POST", "/api/admin/users/list", owner),
                new MockHttpServletResponse(), new Object()));
        MockHttpServletResponse denied = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("POST", "/api/admin/users/save", owner), denied, new Object()));

        when(permissionService.getPermissionCodeSet(owner)).thenReturn(Set.of(PermissionCatalog.ACTION_MEMBER_PERMISSION_WRITE));
        assertTrue(interceptor.preHandle(request("POST", "/api/admin/users/save", owner),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    void productQueryPostIsReadOnlyButBatchDeleteUsesDeletePermission() throws Exception {
        SysUser user = user("OPERATOR");
        when(permissionService.getPermissionCodeSet(user)).thenReturn(Set.of(PermissionCatalog.MENU_GOODS));
        assertTrue(interceptor.preHandle(request("POST", "/api/product-matrix/products/query", user),
                new MockHttpServletResponse(), new Object()));

        MockHttpServletResponse denied = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(
                request("POST", "/api/product-matrix/batches/delete/create", user), denied, new Object()));
        assertEquals(403, denied.getStatus());

        when(permissionService.getPermissionCodeSet(user)).thenReturn(Set.of(
                PermissionCatalog.MENU_GOODS, PermissionCatalog.ACTION_GOODS_DELETE));
        assertTrue(interceptor.preHandle(
                request("POST", "/api/product-matrix/batches/delete/create", user),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    void batchPricePermissionCannotBeBypassedWithUnderscoreRoute() throws Exception {
        SysUser user = user("OPERATOR");
        when(permissionService.getPermissionCodeSet(user)).thenReturn(Set.of(
                PermissionCatalog.MENU_GOODS, PermissionCatalog.ACTION_GOODS_WRITE));
        MockHttpServletResponse denied = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(
                request("POST", "/api/product-matrix/batches/change_price/create", user), denied, new Object()));
        assertEquals(403, denied.getStatus());
    }

    @Test
    void publishingPreflightUsesGoodsMenuAndExecutionNeedsGoodsWrite() throws Exception {
        SysUser user = user("OPERATOR");
        when(permissionService.getPermissionCodeSet(user)).thenReturn(Set.of(PermissionCatalog.MENU_GOODS));

        assertTrue(interceptor.preHandle(request("POST", "/api/publishing/preflight", user),
                new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(request("GET", "/api/publishing/accounts/2/capabilities", user),
                new MockHttpServletResponse(), new Object()));

        MockHttpServletResponse denied = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("POST", "/api/publishing/execute", user), denied, new Object()));
        assertEquals(403, denied.getStatus());

        when(permissionService.getPermissionCodeSet(user)).thenReturn(Set.of(
                PermissionCatalog.MENU_GOODS, PermissionCatalog.ACTION_GOODS_WRITE));
        assertTrue(interceptor.preHandle(request("POST", "/api/publishing/execute", user),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    void refundApproveAndRejectHaveIndependentBackendPermissions() throws Exception {
        SysUser user = user("CUSTOMER_SERVICE");
        when(permissionService.getPermissionCodeSet(user)).thenReturn(Set.of(
                PermissionCatalog.MENU_ORDERS, PermissionCatalog.ACTION_REFUND_APPROVE));

        assertTrue(interceptor.preHandle(
                request("POST", "/api/order-matrix/refunds/2/approve/execute", user),
                new MockHttpServletResponse(), new Object()));
        MockHttpServletResponse rejectDenied = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(
                request("POST", "/api/order-matrix/refunds/2/reject/execute", user), rejectDenied, new Object()));
        assertEquals(403, rejectDenied.getStatus());

        when(permissionService.getPermissionCodeSet(user)).thenReturn(Set.of(
                PermissionCatalog.MENU_ORDERS, PermissionCatalog.ACTION_REFUND_REJECT));
        assertTrue(interceptor.preHandle(
                request("POST", "/api/order-matrix/refunds/2/reject/execute", user),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    void messageWorkspaceReadsNeedMenuAndSendsNeedSendPermission() throws Exception {
        SysUser user = user("CUSTOMER_SERVICE");
        when(permissionService.getPermissionCodeSet(user)).thenReturn(Set.of(PermissionCatalog.MENU_MESSAGES));
        assertTrue(interceptor.preHandle(request("GET", "/api/message-workspace/conversations", user),
                new MockHttpServletResponse(), new Object()));
        MockHttpServletResponse denied = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("POST", "/api/message-workspace/send/text", user),
                denied, new Object()));
        assertEquals(403, denied.getStatus());
    }

    @Test
    void qaMessageFixturesAndHandoffActionsUseSeparateWritePermissions() throws Exception {
        SysUser user = user("CUSTOMER_SERVICE");
        when(permissionService.getPermissionCodeSet(user)).thenReturn(Set.of(PermissionCatalog.MENU_MESSAGES));
        MockHttpServletResponse fixtureDenied = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("POST", "/api/qa/message-workspace/fixtures", user),
                fixtureDenied, new Object()));
        assertEquals(403, fixtureDenied.getStatus());

        when(permissionService.getPermissionCodeSet(user)).thenReturn(Set.of(
                PermissionCatalog.MENU_MESSAGES, PermissionCatalog.ACTION_SYSTEM_WRITE));
        assertTrue(interceptor.preHandle(request("POST", "/api/qa/message-workspace/fixtures", user),
                new MockHttpServletResponse(), new Object()));

        MockHttpServletResponse handoffDenied = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("POST", "/api/message-workspace/handoffs/2/claim", user),
                handoffDenied, new Object()));
        when(permissionService.getPermissionCodeSet(user)).thenReturn(Set.of(
                PermissionCatalog.MENU_MESSAGES, PermissionCatalog.ACTION_MESSAGE_SEND));
        assertTrue(interceptor.preHandle(request("POST", "/api/message-workspace/handoffs/2/claim", user),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    void qaAfterSalesFixtureRequiresOrderWritePermission() throws Exception {
        SysUser support = user("SUPPORT");
        when(permissionService.getPermissionCodeSet(support)).thenReturn(Set.of(PermissionCatalog.MENU_ORDERS));
        MockHttpServletResponse denied = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(
                request("POST", "/api/qa/order-after-sales/fixtures/1", support), denied, new Object()));
        assertEquals(403, denied.getStatus());

        when(permissionService.getPermissionCodeSet(support)).thenReturn(Set.of(
                PermissionCatalog.MENU_ORDERS, PermissionCatalog.ACTION_ORDER_WRITE));
        assertTrue(interceptor.preHandle(
                request("POST", "/api/qa/order-after-sales/fixtures/1", support),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    void backupPreviewExecuteAndRollbackRequireSettingsAndSystemWrite() throws Exception {
        SysUser user = user("OWNER");
        when(permissionService.getPermissionCodeSet(user)).thenReturn(Set.of(PermissionCatalog.MENU_SETTINGS));

        MockHttpServletResponse previewDenied = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("POST", "/api/backup/restore/preview", user), previewDenied, new Object()));
        assertEquals(403, previewDenied.getStatus());
        assertTrue(interceptor.preHandle(request("GET", "/api/backup/restore/jobs/73", user),
                new MockHttpServletResponse(), new Object()));

        when(permissionService.getPermissionCodeSet(user)).thenReturn(Set.of(
                PermissionCatalog.MENU_SETTINGS, PermissionCatalog.ACTION_SYSTEM_WRITE));
        assertTrue(interceptor.preHandle(request("POST", "/api/backup/restore/preview", user),
                new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(request("POST", "/api/backup/restore/execute", user),
                new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(request("POST", "/api/backup/restore/jobs/73/rollback", user),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    void growthWorkspaceSeparatesReadOnlySamplingOperationalWritesAndQaFixtures() throws Exception {
        SysUser user = user("OPERATOR");
        when(permissionService.getPermissionCodeSet(user)).thenReturn(Set.of(PermissionCatalog.MENU_OPERATIONS));

        assertTrue(interceptor.preHandle(request("GET", "/api/growth-workspace/resources", user),
                new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(request("POST", "/api/growth-workspace/searches", user),
                new MockHttpServletResponse(), new Object()));

        MockHttpServletResponse writeDenied = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("POST", "/api/growth-workspace/workflows/versions", user),
                writeDenied, new Object()));
        assertEquals(403, writeDenied.getStatus());

        when(permissionService.getPermissionCodeSet(user)).thenReturn(Set.of(
                PermissionCatalog.MENU_OPERATIONS, PermissionCatalog.ACTION_OPERATIONS_WRITE));
        assertTrue(interceptor.preHandle(request("POST", "/api/growth-workspace/workflows/versions", user),
                new MockHttpServletResponse(), new Object()));

        MockHttpServletResponse qaDenied = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("POST", "/api/qa/growth-workspace/fixtures", user),
                qaDenied, new Object()));
        assertEquals(403, qaDenied.getStatus());

        when(permissionService.getPermissionCodeSet(user)).thenReturn(Set.of(
                PermissionCatalog.MENU_OPERATIONS, PermissionCatalog.ACTION_SYSTEM_WRITE));
        assertTrue(interceptor.preHandle(request("POST", "/api/qa/growth-workspace/fixtures", user),
                new MockHttpServletResponse(), new Object()));
    }

    private SysUser user(String memberRole) {
        SysUser user = new SysUser();
        user.setId(11L);
        user.setRole(SysUser.ROLE_USER);
        user.setMemberRole(memberRole);
        user.setStatus(1);
        return user;
    }

    private MockHttpServletRequest request(String method, String uri, SysUser user) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setAttribute("currentUser", user);
        return request;
    }
}
