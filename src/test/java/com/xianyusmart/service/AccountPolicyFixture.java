package com.xianyusmart.service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Existing reply fixtures use real local execution locks with an ACTIVE account projection. */
public final class AccountPolicyFixture {
    private AccountPolicyFixture() {}
    public static void active(Object service) {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),any(Long.class))).thenReturn(List.of(Map.of("lifecycleState","ACTIVE","autoReplyAllowed",1,"autoDeliveryAllowed",1)));
        ReflectionTestUtils.setField(service,"accountAutomationPolicy",new AccountAutomationPolicy(jdbc));
    }
}
