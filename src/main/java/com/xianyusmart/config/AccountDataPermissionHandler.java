package com.xianyusmart.config;

import com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler;
import com.xianyusmart.context.AccountScopeContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/** 在 SQL 层执行按店授权，避免仅靠前端隐藏造成越权读取或修改。 */
@Component
public class AccountDataPermissionHandler implements MultiDataPermissionHandler {

    private static final Set<String> ACCOUNT_TABLES = Set.of("xianyu_account");
    private static final Set<String> ACCOUNT_ID_TABLES = Set.of(
            "xianyu_cookie", "xianyu_goods", "xianyu_chat_message", "xianyu_goods_config",
            "xianyu_goods_auto_delivery_config", "xianyu_goods_order", "xianyu_goods_auto_reply_record",
            "xianyu_operation_log", "xianyu_kami_config", "xianyu_kami_usage_record",
            "xianyu_keyword_reply_rule", "xianyu_goods_sku", "xianyu_goods_sku_property",
            "xianyu_human_intervention_record", "xianyu_buyer_profile", "xianyu_kami_external_request",
            "xianyu_order_confirmation", "xianyu_device_profile", "operational_issue",
            "xianyu_account_capability", "conversation_assignment"
    );
    private static final Set<String> OPTIONAL_ACCOUNT_TABLES = Set.of(
            "merchant_resource", "merchant_task", "merchant_distribution"
    );

    @Override
    public Expression getSqlSegment(Table table, Expression where, String mappedStatementId) {
        AccountScopeContext.Scope scope = AccountScopeContext.get();
        if (scope == null || scope.unrestricted()) {
            return null;
        }
        String tableName = table.getName().toLowerCase();
        String qualifier = table.getAlias() == null ? table.getName() : table.getAlias().getName();
        String column;
        boolean optional = false;
        if (ACCOUNT_TABLES.contains(tableName)) {
            column = qualifier + ".id";
        } else if (ACCOUNT_ID_TABLES.contains(tableName)) {
            column = qualifier + ".xianyu_account_id";
        } else if (OPTIONAL_ACCOUNT_TABLES.contains(tableName)) {
            column = qualifier + ".xianyu_account_id";
            optional = true;
        } else {
            return null;
        }
        String condition;
        if (scope.accountIds().isEmpty()) {
            condition = optional ? column + " IS NULL" : "1 = 0";
        } else {
            String ids = scope.accountIds().stream().sorted().map(String::valueOf)
                    .collect(Collectors.joining(","));
            condition = optional
                    ? "(" + column + " IS NULL OR " + column + " IN (" + ids + "))"
                    : column + " IN (" + ids + ")";
        }
        try {
            return CCJSqlParserUtil.parseCondExpression(condition);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成账号数据权限条件", e);
        }
    }
}
