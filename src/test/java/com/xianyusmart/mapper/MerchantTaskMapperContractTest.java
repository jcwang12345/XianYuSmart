package com.xianyusmart.mapper;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantTaskMapperContractTest {

    @Test
    void completeUsesExplicitVerificationOutcomeAndRecoveryEvidence() throws Exception {
        Update annotation = MerchantTaskMapper.class
                .getMethod("complete", Long.class, String.class)
                .getAnnotation(Update.class);
        String sql = String.join(" ", Arrays.asList(annotation.value()));

        assertTrue(sql.contains("$.verificationStatus"));
        assertTrue(sql.contains("$.outcomeState"));
        assertTrue(sql.contains("$.recoveryHint"));
    }
}
