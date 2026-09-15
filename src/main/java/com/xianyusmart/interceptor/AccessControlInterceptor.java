package com.xianyusmart.interceptor;

import com.google.gson.Gson;
import com.xianyusmart.common.ResultObject;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.service.PermissionCatalog;
import com.xianyusmart.service.PlatformPermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 菜单与功能权限拦截器
 */
@Component
public class AccessControlInterceptor implements HandlerInterceptor {

    private static final Set<String> ITEM_READ_PATHS = Set.of(
            "/api/items/list", "/api/items/detail", "/api/items/autodeliveryrecords",
            "/api/items/autoreplyrecords", "/api/items/getragautoreplyconfig",
            "/api/items/updateragautoreplyconfig");
    private static final Set<String> KAMI_READ_PATHS = Set.of(
            "/api/kami-config/list", "/api/kami-config/detail", "/api/kami-config/item/list",
            "/api/kami-config/item/query", "/api/kami-config/item/export");
    private static final Set<String> ORDER_READ_PATHS = Set.of(
            "/api/order/list", "/api/order/detail", "/api/order/ratedetails");
    private static final Set<String> AI_READ_PATHS = Set.of(
            "/ai/status", "/ai/queryragdata", "/ai/getfixedmaterial", "/ai/chat", "/ai/chattest");
    private static final Set<String> ADMIN_USER_READ_PATHS = Set.of(
            "/api/admin/users/list", "/api/admin/users/permissions");

    private final PlatformPermissionService permissionService;
    private final Gson gson = new Gson();

    public AccessControlInterceptor(PlatformPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        SysUser currentUser = (SysUser) request.getAttribute("currentUser");
        String uri = request.getRequestURI().toLowerCase();
        boolean isAdmin = currentUser != null
                && Integer.valueOf(1).equals(currentUser.getStatus())
                && SysUser.ROLE_ADMIN.equalsIgnoreCase(currentUser.getRole());
        if (uri.startsWith("/api/admin/users") && isTenantManager(currentUser)) {
            if (ADMIN_USER_READ_PATHS.contains(uri) || "GET".equalsIgnoreCase(request.getMethod()) || isAdmin) {
                return true;
            }
            if (permissionService.getPermissionCodeSet(currentUser)
                    .contains(PermissionCatalog.ACTION_MEMBER_PERMISSION_WRITE)) {
                return true;
            }
            writeForbidden(response, "当前账号没有修改成员权限的功能权限");
            return false;
        }
        if (uri.startsWith("/api/admin")) {
            if (!isAdmin) {
                writeForbidden(response, "仅平台管理员可以管理账号与权限");
                return false;
            }
            return true;
        }
        if (isAdmin) {
            return true;
        }
        Set<String> permissions = permissionService.getPermissionCodeSet(currentUser);
        String menuPermission = resolveMenuPermission(uri);
        boolean hasMenuPermission = "/api/order/ratedetails".equals(uri)
                ? permissions.contains(PermissionCatalog.MENU_BUYERS)
                    || permissions.contains(PermissionCatalog.MENU_ORDERS)
                : hasPermission(permissions, menuPermission);
        if (!hasMenuPermission) {
            writeForbidden(response, "当前账号没有此菜单的访问权限");
            return false;
        }
        String actionPermission = resolveActionPermission(request.getMethod(), uri);
        if (!hasPermission(permissions, actionPermission)) {
            writeForbidden(response, "当前账号没有执行此操作的功能权限");
            return false;
        }
        return true;
    }

    private boolean isTenantManager(SysUser user) {
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) return false;
        if (SysUser.ROLE_ADMIN.equalsIgnoreCase(user.getRole())) return true;
        return "OWNER".equalsIgnoreCase(user.getMemberRole())
                || "TENANT_ADMIN".equalsIgnoreCase(user.getMemberRole());
    }

    private boolean hasPermission(Set<String> permissions, String permissionCode) {
        return permissionCode == null || permissionCode.isBlank() || permissions.contains(permissionCode);
    }

    private String resolveMenuPermission(String uri) {
        if (uri.startsWith("/api/qa/backup")) return PermissionCatalog.MENU_SETTINGS;
        if (uri.startsWith("/api/qa/notification-trace")) return PermissionCatalog.MENU_HEALTH;
        if (uri.startsWith("/api/qa/message-workspace")) return PermissionCatalog.MENU_MESSAGES;
        if (uri.startsWith("/api/qa/business-analytics")) return PermissionCatalog.MENU_DASHBOARD;
        if (uri.startsWith("/api/qa/fulfillment")) return PermissionCatalog.MENU_KAMI;
        if (uri.startsWith("/api/qa/growth-workspace")) return PermissionCatalog.MENU_OPERATIONS;
        if (uri.startsWith("/api/growth-workspace")) return PermissionCatalog.MENU_OPERATIONS;
        if (uri.startsWith("/api/business-analytics")) return PermissionCatalog.MENU_DASHBOARD;
        if (uri.startsWith("/api/account-groups")) return PermissionCatalog.MENU_ACCOUNTS;
        if (uri.startsWith("/api/message-workspace")) return PermissionCatalog.MENU_MESSAGES;
        if (uri.startsWith("/api/qa/order-after-sales")) return PermissionCatalog.MENU_ORDERS;
        if (uri.startsWith("/api/order-matrix")) return PermissionCatalog.MENU_ORDERS;
        if (uri.startsWith("/api/publishing")) return PermissionCatalog.MENU_GOODS;
        if (uri.startsWith("/api/product-matrix")) return PermissionCatalog.MENU_GOODS;
        if (uri.startsWith("/api/account-matrix")) return PermissionCatalog.MENU_ACCOUNTS;
        if (uri.startsWith("/api/automation-assist/reply-preference")) return PermissionCatalog.MENU_AUTO_REPLY;
        if (uri.startsWith("/api/reply-policy")) return PermissionCatalog.MENU_AUTO_REPLY;
        if (uri.startsWith("/api/automation-assist/skus")) return PermissionCatalog.MENU_AUTO_DELIVERY;
        if (uri.startsWith("/api/automation-assist/")) return PermissionCatalog.MENU_ORDERS;
        // 账号选择器、商品基础资料和连接状态被多个页面复用，由各业务页面权限决定是否可见。
        if (uri.equals("/api/account/list") || uri.equals("/api/websocket/status")
                || uri.equals("/api/items/list") || uri.equals("/api/items/detail")
                || uri.startsWith("/api/goods-sku")) {
            return null;
        }
        if (uri.equals("/api/websocket/sendmessage") || uri.equals("/api/websocket/sendimagemessage")) {
            return PermissionCatalog.MENU_MESSAGES;
        }
        if (uri.startsWith("/api/dashboard") || uri.startsWith("/api/data-panel")) {
            return PermissionCatalog.MENU_DASHBOARD;
        }
        if (uri.startsWith("/api/command-center")) {
            return PermissionCatalog.MENU_COMMAND_CENTER;
        }
        if (uri.startsWith("/api/items/autodeliveryrecords") || uri.startsWith("/api/order")) {
            return PermissionCatalog.MENU_ORDERS;
        }
        if (uri.startsWith("/api/items/autoreplyrecords")
                || uri.startsWith("/api/items/getragautoreplyconfig")
                || uri.startsWith("/api/items/updateragautoreplyconfig")
                || uri.startsWith("/api/keyword-reply") || uri.startsWith("/ai")) {
            return PermissionCatalog.MENU_AUTO_REPLY;
        }
        if (uri.startsWith("/api/account") || uri.startsWith("/api/qrlogin")) {
            return PermissionCatalog.MENU_ACCOUNTS;
        }
        if (uri.startsWith("/api/security")) {
            return PermissionCatalog.MENU_SETTINGS;
        }
        if (uri.startsWith("/api/websocket")) {
            return PermissionCatalog.MENU_CONNECTION;
        }
        if (uri.startsWith("/api/items") || uri.startsWith("/api/goods-sku") || uri.startsWith("/api/image")) {
            return PermissionCatalog.MENU_GOODS;
        }
        if (uri.startsWith("/api/merchant")) {
            return PermissionCatalog.MENU_OPERATIONS;
        }
        if (uri.startsWith("/api/msg")) {
            return PermissionCatalog.MENU_MESSAGES;
        }
        if (uri.startsWith("/api/buyers")) {
            return PermissionCatalog.MENU_BUYERS;
        }
        if (uri.startsWith("/api/kami-config")) {
            return PermissionCatalog.MENU_KAMI;
        }
        if (uri.startsWith("/api/fixed-delivery-template")) {
            return PermissionCatalog.MENU_FIXED_DELIVERY;
        }
        if (uri.startsWith("/api/auto-delivery-config") || uri.startsWith("/api/autodelivery")) {
            return PermissionCatalog.MENU_AUTO_DELIVERY;
        }
        if (uri.startsWith("/api/operation-log")) {
            return PermissionCatalog.MENU_OPERATION_LOG;
        }
        if (uri.startsWith("/api/diagnostics") || uri.startsWith("/api/notifications")) {
            return PermissionCatalog.MENU_HEALTH;
        }
        if (uri.startsWith("/api/setting") || uri.startsWith("/api/backup")) {
            return PermissionCatalog.MENU_SETTINGS;
        }
        return null;
    }

    private String resolveActionPermission(String method, String uri) {
        if (uri.startsWith("/api/qa/growth-workspace") && !"GET".equalsIgnoreCase(method)) {
            return PermissionCatalog.ACTION_SYSTEM_WRITE;
        }
        // 商机采样虽然使用 POST 承载查询条件，但只读取公开平台样本并写本地证据快照，
        // 不执行店铺侧动作，因此只要求运营菜单读取权限。
        if (uri.equals("/api/growth-workspace/searches")) {
            return null;
        }
        if (uri.startsWith("/api/growth-workspace") && !"GET".equalsIgnoreCase(method)) {
            return PermissionCatalog.ACTION_OPERATIONS_WRITE;
        }
        if (uri.startsWith("/api/qa/backup") && !"GET".equalsIgnoreCase(method)) {
            return PermissionCatalog.ACTION_SYSTEM_WRITE;
        }
        if (uri.startsWith("/api/reply-policy/") && !"GET".equalsIgnoreCase(method)) {
            return PermissionCatalog.ACTION_AUTOMATION_WRITE;
        }
        if ((uri.startsWith("/api/keyword-reply") || uri.startsWith("/ai"))
                && !"GET".equalsIgnoreCase(method)) {
            return PermissionCatalog.ACTION_AUTOMATION_WRITE;
        }
        if (uri.startsWith("/api/qa/notification-trace") && !"GET".equalsIgnoreCase(method)) {
            return PermissionCatalog.ACTION_SYSTEM_WRITE;
        }
        if (uri.startsWith("/api/qa/message-workspace") && !"GET".equalsIgnoreCase(method)) {
            return PermissionCatalog.ACTION_SYSTEM_WRITE;
        }
        if (uri.startsWith("/api/qa/business-analytics") && !"GET".equalsIgnoreCase(method)) {
            return PermissionCatalog.ACTION_SYSTEM_WRITE;
        }
        if (uri.startsWith("/api/qa/fulfillment") && !"GET".equalsIgnoreCase(method)) {
            return PermissionCatalog.ACTION_DELIVERY_WRITE;
        }
        if (uri.equals("/api/business-analytics/refresh-local") && !"GET".equalsIgnoreCase(method)) {
            return PermissionCatalog.ACTION_SYSTEM_WRITE;
        }
        if (uri.startsWith("/api/account-groups") && !"GET".equalsIgnoreCase(method)) {
            return PermissionCatalog.ACTION_ACCOUNT_WRITE;
        }
        if (uri.startsWith("/api/message-workspace/send/")
                || (uri.startsWith("/api/message-workspace/send-attempts/") && !"GET".equalsIgnoreCase(method))
                || uri.equals("/api/message-workspace/conversation/takeover")
                || (uri.startsWith("/api/message-workspace/handoffs/") && !"GET".equalsIgnoreCase(method))) {
            return PermissionCatalog.ACTION_MESSAGE_SEND;
        }
        if (uri.equals("/api/message-workspace/conversation/update")) return PermissionCatalog.ACTION_BUYER_WRITE;
        if (uri.startsWith("/api/qa/order-after-sales") && !"GET".equalsIgnoreCase(method)) {
            return PermissionCatalog.ACTION_ORDER_WRITE;
        }
        if (uri.startsWith("/api/order-matrix/refunds/") && uri.contains("/approve/")
                && uri.endsWith("/execute")) return PermissionCatalog.ACTION_REFUND_APPROVE;
        if (uri.startsWith("/api/order-matrix/refunds/") && uri.contains("/reject/")
                && uri.endsWith("/execute")) return PermissionCatalog.ACTION_REFUND_REJECT;
        if (uri.startsWith("/api/order-matrix/") && !"GET".equalsIgnoreCase(method)
                && !uri.endsWith("/query") && !uri.endsWith("/preview")) {
            return PermissionCatalog.ACTION_ORDER_WRITE;
        }
        if (uri.equals("/api/publishing/execute")) return PermissionCatalog.ACTION_GOODS_WRITE;
        if (uri.startsWith("/api/product-matrix/batches/delete/")) {
            return PermissionCatalog.ACTION_GOODS_DELETE;
        }
        if (uri.startsWith("/api/product-matrix/batches/change-price/")
                || uri.startsWith("/api/product-matrix/batches/change_price/")) {
            return PermissionCatalog.ACTION_GOODS_BATCH_PRICE;
        }
        if (uri.startsWith("/api/product-matrix/batches/")
                && (uri.endsWith("/create") || uri.endsWith("/retry") || uri.endsWith("/cancel"))) {
            return PermissionCatalog.ACTION_GOODS_WRITE;
        }
        if (uri.contains("/api/product-matrix/accounts/") && uri.endsWith("/raw-snapshot")) {
            return PermissionCatalog.ACTION_AUDIT_EXPORT;
        }
        if (uri.startsWith("/api/product-matrix/accounts/") && !"GET".equalsIgnoreCase(method)) {
            return PermissionCatalog.ACTION_GOODS_WRITE;
        }
        if (uri.startsWith("/api/product-matrix/filters") && !"GET".equalsIgnoreCase(method)) {
            return PermissionCatalog.ACTION_GOODS_WRITE;
        }
        if (uri.equals("/api/product-matrix/products/export")) return PermissionCatalog.ACTION_GOODS_WRITE;
        if (uri.equals("/api/account/delete")) {
            return PermissionCatalog.ACTION_ACCOUNT_DELETE;
        }
        if (uri.startsWith("/api/account-matrix/batch")) {
            return PermissionCatalog.ACTION_ACCOUNT_BATCH;
        }
        if (uri.startsWith("/api/account-matrix/") && uri.endsWith("/risks/export")) {
            return PermissionCatalog.ACTION_RISK_EXPORT;
        }
        if (uri.startsWith("/api/account-matrix/risks/") && uri.endsWith("/handling")) {
            return PermissionCatalog.ACTION_RISK_HANDLE;
        }
        if (uri.startsWith("/api/account-matrix/") && !"GET".equalsIgnoreCase(method)) {
            return PermissionCatalog.ACTION_ACCOUNT_WRITE;
        }
        if (uri.equals("/api/items/delete")) {
            return PermissionCatalog.ACTION_GOODS_DELETE;
        }
        if (uri.startsWith("/api/items/batch-price")) {
            return PermissionCatalog.ACTION_GOODS_BATCH_PRICE;
        }
        if (uri.equals("/api/kami-config/item/export")) {
            return PermissionCatalog.ACTION_KAMI_EXPORT;
        }
        if (uri.startsWith("/api/operation-log/export")) {
            return PermissionCatalog.ACTION_AUDIT_EXPORT;
        }
        if (uri.startsWith("/api/order/refunds/") && uri.endsWith("/approve")) {
            return PermissionCatalog.ACTION_REFUND_APPROVE;
        }
        if (uri.startsWith("/api/order/refunds/") && uri.endsWith("/reject")) {
            return PermissionCatalog.ACTION_REFUND_REJECT;
        }
        if (!"GET".equalsIgnoreCase(method) && uri.startsWith("/api/automation-assist/")) {
            if (uri.endsWith("reply-preference")) return PermissionCatalog.ACTION_AUTOMATION_WRITE;
            if (uri.contains("/skus")) return PermissionCatalog.ACTION_GOODS_WRITE;
            return PermissionCatalog.ACTION_ORDER_WRITE;
        }
        if (uri.startsWith("/api/account/")
                && !uri.equals("/api/account/list") && !uri.equals("/api/account/detail")) {
            return PermissionCatalog.ACTION_ACCOUNT_WRITE;
        }
        if (uri.startsWith("/api/qrlogin")) {
            return PermissionCatalog.ACTION_CREDENTIAL_WRITE;
        }
        if (uri.equals("/api/websocket/sendmessage") || uri.equals("/api/websocket/sendimagemessage")) {
            return PermissionCatalog.ACTION_MESSAGE_SEND;
        }
        if ((uri.startsWith("/api/websocket/updatecookie")
                || uri.startsWith("/api/websocket/refreshtoken")
                || uri.startsWith("/api/websocket/updatetoken")
                || uri.startsWith("/api/websocket/refreshcookie"))) {
            return PermissionCatalog.ACTION_CREDENTIAL_WRITE;
        }
        if (uri.startsWith("/api/websocket")
                && !uri.equals("/api/websocket/status") && !uri.equals("/api/websocket/checklogin")) {
            return PermissionCatalog.ACTION_CONNECTION_WRITE;
        }
        if ((uri.startsWith("/api/items") && !ITEM_READ_PATHS.contains(uri)
                && !uri.startsWith("/api/items/syncprogress/") && !uri.startsWith("/api/items/syncing/"))
                || uri.startsWith("/api/image")) {
            return PermissionCatalog.ACTION_GOODS_WRITE;
        }
        if (uri.startsWith("/api/merchant") && !"GET".equalsIgnoreCase(method)) {
            return PermissionCatalog.ACTION_OPERATIONS_WRITE;
        }
        if (uri.equals("/api/buyers/save")) {
            return PermissionCatalog.ACTION_BUYER_WRITE;
        }
        boolean kamiEventRead = "GET".equalsIgnoreCase(method)
                && uri.matches("/api/kami-config/\\d+/events");
        boolean kamiExternalRequestRead = "GET".equalsIgnoreCase(method)
                && uri.matches("/api/kami-config/\\d+/external/requests");
        boolean fixedTemplateRead = "GET".equalsIgnoreCase(method)
                || uri.equals("/api/fixed-delivery-template/preview");
        if ((uri.startsWith("/api/kami-config") && !KAMI_READ_PATHS.contains(uri)
                && !kamiEventRead && !kamiExternalRequestRead)
                || (uri.startsWith("/api/fixed-delivery-template") && !fixedTemplateRead)
                || (uri.startsWith("/api/auto-delivery-config")
                    && !uri.endsWith("/get") && !uri.endsWith("/list") && !uri.endsWith("/listbygoods"))
                || uri.startsWith("/api/autodelivery")) {
            return PermissionCatalog.ACTION_DELIVERY_WRITE;
        }
        if (uri.startsWith("/api/order") && !ORDER_READ_PATHS.contains(uri)) {
            return PermissionCatalog.ACTION_ORDER_WRITE;
        }
        if ((uri.startsWith("/api/keyword-reply") && !uri.endsWith("/rules"))
                || (uri.startsWith("/ai") && !AI_READ_PATHS.contains(uri))
                || uri.equals("/api/items/updateragautoreplyconfig")) {
            return PermissionCatalog.ACTION_AUTOMATION_WRITE;
        }
        if ((uri.startsWith("/api/operation-log") && !uri.endsWith("/query"))
                || (uri.startsWith("/api/diagnostics") && !"GET".equalsIgnoreCase(method))
                || (uri.startsWith("/api/command-center") && !"GET".equalsIgnoreCase(method))
                || (uri.startsWith("/api/notifications") && !"GET".equalsIgnoreCase(method))
                || (uri.startsWith("/api/setting") && !uri.endsWith("/get") && !uri.endsWith("/list"))
                || (uri.startsWith("/api/security") && !"GET".equalsIgnoreCase(method))
                || uri.equals("/api/backup/import")
                || (uri.startsWith("/api/backup/restore") && !"GET".equalsIgnoreCase(method))) {
            return PermissionCatalog.ACTION_SYSTEM_WRITE;
        }
        return null;
    }

    private void writeForbidden(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ResultObject<?> result = ResultObject.forbidden(null);
        result.setMsg(message);
        response.getWriter().write(gson.toJson(result));
    }
}
