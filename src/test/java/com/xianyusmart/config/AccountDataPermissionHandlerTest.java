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
}
