package com.xianyusmart.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.context.TenantContext;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * MyBatis Plus配置类
 */
@Configuration
public class MybatisPlusConfig {

    private final AccountDataPermissionHandler accountDataPermissionHandler = new AccountDataPermissionHandler();

    private static final Set<String> TENANT_TABLES = Set.of(
            "xianyu_account", "xianyu_cookie", "xianyu_goods", "xianyu_chat_message",
            "xianyu_goods_config", "xianyu_goods_auto_delivery_config", "xianyu_goods_order",
            "xianyu_fixed_delivery_template",
            "xianyu_goods_auto_reply_record", "xianyu_operation_log", "xianyu_sys_setting",
            "xianyu_kami_config", "xianyu_kami_item", "xianyu_kami_usage_record",
            "xianyu_keyword_reply_rule", "xianyu_keyword_reply_content", "xianyu_goods_sku",
            "xianyu_goods_sku_property", "xianyu_human_intervention_record",
            "xianyu_buyer_profile", "xianyu_notification_channel", "xianyu_notification_log",
            "xianyu_notification_outbox",
            "xianyu_kami_external_request",
            "merchant_resource", "merchant_task", "merchant_distribution", "merchant_short_link",
            "merchant_resource_account", "xianyu_keyword_reply_rule_account",
            "xianyu_fixed_delivery_template_account", "xianyu_kami_config_account",
            "xianyu_device_profile"
            ,"xianyu_order_confirmation", "xianyu_reply_preference", "xianyu_welcome_claim",
            "operational_issue", "xianyu_account_capability", "conversation_assignment",
            "xianyu_account_access_channel", "xianyu_account_dataset_state", "xianyu_shop_profile_snapshot",
            "xianyu_shop_risk_event", "xianyu_shop_risk_action",
            "xianyu_goods_event", "xianyu_goods_metric_daily",
            "xianyu_goods_batch_job", "xianyu_goods_batch_item",
            "xianyu_order_event", "xianyu_refund_case", "xianyu_refund_action", "xianyu_return_shipment",
            "xianyu_message_send_attempt", "xianyu_notification_event",
            "xianyu_ai_handoff_task",
            "xianyu_account_group", "xianyu_account_group_member", "xianyu_shop_metric_daily",
            "sys_user_account_group_scope", "xianyu_saved_filter"
    );

    /**
     * 分页插件
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                Long tenantId = TenantContext.get();
                return new LongValue(tenantId == null ? 0L : tenantId);
            }

            @Override
            public boolean ignoreTable(String tableName) {
                // 定时任务没有登录上下文，按账号归属处理全部租户数据。
                return TenantContext.get() == null || !TENANT_TABLES.contains(tableName.toLowerCase());
            }
        }));
        interceptor.addInnerInterceptor(new DataPermissionInterceptor(accountDataPermissionHandler));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
