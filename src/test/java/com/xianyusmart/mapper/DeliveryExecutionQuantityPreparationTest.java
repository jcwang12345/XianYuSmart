package com.xianyusmart.mapper;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryExecutionQuantityPreparationTest {
    @Test
    void quantityPreparationOnlyUpdatesQuantityInsideCurrentUnstartedFirstDeliveryLease() throws Exception {
        String sql = String.join(" ", DeliveryExecutionMapper.class
                .getMethod("prepareCardQuantity", Long.class, String.class, int.class)
                .getAnnotation(Update.class).value()).replaceAll("\\s+", " ");
        assertTrue(sql.startsWith("UPDATE xianyu_goods_order SET expected_quantity=#{quantity}, buy_num=#{quantity} WHERE "));
        assertTrue(sql.contains("id=#{id}"));
        assertTrue(sql.contains("lease_owner=#{token}"));
        assertTrue(sql.contains("lease_expire_time>NOW(3)"));
        assertTrue(sql.contains("delivery_status='PROCESSING'"));
        assertTrue(sql.contains("external_attempt_started=0"));
        assertTrue(sql.contains("state<>1"));
        assertTrue(sql.contains("confirm_state=0"));
        assertTrue(sql.contains("delivery_message_state=0"));
        assertTrue(sql.contains("delivered_quantity=0"));
        assertTrue(sql.contains("(refund_status IS NULL OR refund_status='NONE')"));
        assertTrue(sql.contains("#{quantity}>0"));
        assertFalse(sql.contains("SET state="));
        assertFalse(sql.contains("SET delivery_status="));
        assertFalse(sql.contains("SET external_attempt_started="));
        assertFalse(sql.contains("SET delivered_quantity="));
    }
}
