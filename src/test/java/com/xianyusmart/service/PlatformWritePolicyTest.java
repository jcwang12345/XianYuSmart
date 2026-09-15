package com.xianyusmart.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformWritePolicyTest {
    @Test void qaPolicyBlocksKnownWritesButNotReads() {
        PlatformWritePolicy policy = new PlatformWritePolicy(false);
        assertTrue(policy.blocksApi("mtop.idle.pc.idleitem.edit"));
        assertTrue(policy.blocksApi("mtop.taobao.idle.merchant.rate.create"));
        assertTrue(policy.blocksApi("mtop.taobao.idle.logistics.merchant.consign.dummy"));
        assertFalse(policy.blocksApi("mtop.taobao.idle.merchant.rate.list"));
    }

    @Test void productionPolicyAllowsWrites() {
        assertFalse(new PlatformWritePolicy(true)
                .blocksApi("mtop.taobao.idle.merchant.rate.create"));
    }
}
