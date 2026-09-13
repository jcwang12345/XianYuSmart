package com.xianyusmart.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionCatalogTest {

    @Test
    void highRiskActionsAreIndependentlyAssignable() {
        assertTrue(PermissionCatalog.codes().contains(PermissionCatalog.ACTION_ACCOUNT_DELETE));
        assertTrue(PermissionCatalog.codes().contains(PermissionCatalog.ACTION_CREDENTIAL_WRITE));
        assertTrue(PermissionCatalog.codes().contains(PermissionCatalog.ACTION_ACCOUNT_BATCH));
        assertTrue(PermissionCatalog.codes().contains(PermissionCatalog.ACTION_RISK_EXPORT));
        assertTrue(PermissionCatalog.codes().contains(PermissionCatalog.ACTION_RISK_HANDLE));
        assertTrue(PermissionCatalog.codes().contains(PermissionCatalog.ACTION_GOODS_DELETE));
        assertTrue(PermissionCatalog.codes().contains(PermissionCatalog.ACTION_GOODS_BATCH_PRICE));
        assertTrue(PermissionCatalog.codes().contains(PermissionCatalog.ACTION_REFUND_APPROVE));
        assertTrue(PermissionCatalog.codes().contains(PermissionCatalog.ACTION_REFUND_REJECT));
        assertTrue(PermissionCatalog.codes().contains(PermissionCatalog.ACTION_KAMI_EXPORT));
        assertTrue(PermissionCatalog.codes().contains(PermissionCatalog.ACTION_AUDIT_EXPORT));
        assertTrue(PermissionCatalog.codes().contains(PermissionCatalog.ACTION_MEMBER_PERMISSION_WRITE));
    }
}
