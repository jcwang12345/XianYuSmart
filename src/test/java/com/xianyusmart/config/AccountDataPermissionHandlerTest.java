package com.xianyusmart.config;

import com.xianyusmart.context.AccountScopeContext;
import net.sf.jsqlparser.schema.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AccountDataPermissionHandlerTest {

    private final AccountDataPermissionHandler handler = new AccountDataPermissionHandler();

    @AfterEach
    void clearScope() {
        AccountScopeContext.clear();
    }

    @Test
    void selectedScopeAddsAccountPredicate() {
        AccountScopeContext.set(false, Set.of(9L, 3L));
        assertEquals("xianyu_goods.xianyu_account_id IN (3, 9)",
                handler.getSqlSegment(new Table("xianyu_goods"), null, "test").toString());
    }

    @Test
    void unrestrictedScopeDoesNotChangeSql() {
        AccountScopeContext.set(true, Set.of());
        assertNull(handler.getSqlSegment(new Table("xianyu_goods"), null, "test"));
    }

    @Test
    void accountMatrixTablesAreProtectedBySelectedScope() {
        AccountScopeContext.set(false, Set.of(7L));
        for (String tableName : Set.of("xianyu_account_access_channel", "xianyu_account_dataset_state",
                "xianyu_shop_profile_snapshot", "xianyu_shop_risk_event", "xianyu_shop_risk_action")) {
            assertEquals(tableName + ".xianyu_account_id IN (7)",
                    handler.getSqlSegment(new Table(tableName), null, "test").toString());
        }
    }

    @Test
    void growthWorkspaceAccountTablesAreProtectedBySelectedScope() {
        AccountScopeContext.set(false, Set.of(101L, 103L));
        for (String tableName : Set.of("growth_resource_goods_mapping", "growth_search_snapshot",
                "growth_workflow_run")) {
            assertEquals(tableName + ".xianyu_account_id IN (101, 103)",
                    handler.getSqlSegment(new Table(tableName), null, "test").toString());
        }
    }
}
