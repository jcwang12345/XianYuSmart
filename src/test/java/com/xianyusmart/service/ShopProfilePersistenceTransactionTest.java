package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ShopProfilePersistenceTransactionTest {

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void datasetChannelAndAuditFailureInjectionRollsBackTheWholeSnapshotUnit() throws Exception {
        assertTrue(ShopProfilePersistenceService.class.getMethod("completeSuccess", Long.class, String.class,
                String.class, ShopProfileParser.ParsedProfile.class).isAnnotationPresent(Transactional.class));
        TenantContext.set(1L);
        ShopProfileParser parser = new ShopProfileParser(new ObjectMapper());
        ShopProfileParser.ParsedProfile profile = parser.parse("u1",
                "{\"ret\":[\"SUCCESS\"],\"data\":{\"baseInfo\":{\"kcUserId\":\"u1\",\"tags\":{}},\"module\":{}}}",
                "{\"ret\":[\"SUCCESS\"],\"data\":{}}", "{\"ret\":[\"SUCCESS\"],\"data\":{}}");

        for (String failure : List.of("dataset", "channel", "audit")) {
            RecordingDatabase database = new RecordingDatabase();
            RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(database,
                    "dataset".equals(failure) ? "xianyu_account_dataset_state"
                            : "channel".equals(failure) ? "xianyu_account_access_channel" : null);
            OperationLogService audit = mock(OperationLogService.class);
            if ("audit".equals(failure)) doThrow(new IllegalStateException("audit injected")).when(audit).logRequired(any());
            ShopProfilePersistenceService persistence = new ShopProfilePersistenceService(jdbc,
                    new ObjectMapper(), audit);
            TransactionTemplate transaction = new TransactionTemplate(new SnapshotTransactionManager(database));

            assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(
                    ignored -> persistence.completeSuccess(3L, "request-" + failure, "PLATFORM_API", profile)));
            assertEquals(0, database.writes, failure + " failure must leave no snapshot/dataset/channel write");
        }
    }

    private static final class RecordingDatabase { int writes; }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final RecordingDatabase database;
        private final String failToken;
        private RecordingJdbcTemplate(RecordingDatabase database, String failToken) {
            this.database = database; this.failToken = failToken;
        }
        @Override public List<Map<String, Object>> queryForList(String sql, Object... args) { return List.of(); }
        @Override public int update(String sql, Object... args) {
            database.writes++;
            if (failToken != null && sql.contains(failToken)) throw new IllegalStateException("injected " + failToken);
            return 1;
        }
    }

    private static final class SnapshotTransactionManager implements PlatformTransactionManager {
        private final RecordingDatabase database;
        private final ThreadLocal<Integer> before = new ThreadLocal<>();
        private SnapshotTransactionManager(RecordingDatabase database) { this.database = database; }
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            before.set(database.writes); return new SimpleTransactionStatus();
        }
        @Override public void commit(TransactionStatus status) { before.remove(); }
        @Override public void rollback(TransactionStatus status) {
            database.writes = before.get(); before.remove();
        }
    }
}
