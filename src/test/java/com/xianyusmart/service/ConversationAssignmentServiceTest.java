package com.xianyusmart.service;

import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.context.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationAssignmentServiceTest {

    private JdbcTemplate jdbcTemplate;
    private ConversationAssignmentService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new ConversationAssignmentService(jdbcTemplate);
        TenantContext.set(6L);
        UserContext.set(4L, "message-tester", 6L);
        AccountScopeContext.clear();
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
    }

    @AfterEach
    void tearDown() {
        AccountScopeContext.clear();
        TenantContext.clear();
        UserContext.clear();
    }

    @Test
    void refreshUsesMySql57CompatibleAggregateInHavingClause() {
        service.refresh();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).update(sqlCaptor.capture(), any(Object[].class));
        List<String> statements = sqlCaptor.getAllValues();
        String insert = statements.stream()
                .filter(sql -> sql.contains("INSERT INTO conversation_assignment"))
                .findFirst()
                .orElseThrow();

        assertTrue(insert.contains("HAVING MIN(CASE WHEN messages.sender_user_id <> account.unb"));
        assertFalse(insert.contains("HAVING first_message_time"));
    }
}
