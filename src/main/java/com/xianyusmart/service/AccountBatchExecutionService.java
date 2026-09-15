package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.mapper.XianyuAccountMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Executes one account lifecycle operation. QA fixtures never touch the platform. */
@Service
public class AccountBatchExecutionService {
    private final XianyuAccountMapper accounts;
    private final WebSocketService webSocket;
    private final CredentialRenewalService renewal;
    private final boolean qaMockEnabled;
    private final long qaTenantId;
    private final Set<Long> qaAccountIds;

    public AccountBatchExecutionService(XianyuAccountMapper accounts,
                                        WebSocketService webSocket,
                                        CredentialRenewalService renewal,
                                        @Value("${app.account-batch.qa-mock.enabled:false}") boolean qaMockEnabled,
                                        @Value("${app.account-batch.qa-mock.tenant-id:-1}") long qaTenantId,
                                        @Value("${app.account-batch.qa-mock.account-ids:}") String qaAccountIds) {
        this.accounts = accounts;
        this.webSocket = webSocket;
        this.renewal = renewal;
        this.qaMockEnabled = qaMockEnabled;
        this.qaTenantId = qaTenantId;
        this.qaAccountIds = Arrays.stream(qaAccountIds.split(","))
                .map(String::trim).filter(value -> value.matches("\\d+"))
                .map(Long::valueOf).collect(Collectors.toUnmodifiableSet());
    }

    public boolean isQaEligible(Long tenantId, Long accountId) {
        return qaMockEnabled && tenantId != null && tenantId == qaTenantId && qaAccountIds.contains(accountId);
    }

    public Map<String, Object> execute(String operation, Long accountId) {
        XianyuAccount account = accounts.selectById(accountId);
        if (account == null) throw new IllegalArgumentException("账号不存在或无权访问");
        boolean qaMock = isQaEligible(TenantContext.get(), accountId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountId", accountId);
        result.put("operation", operation);
        result.put("executionChannel", qaMock ? "QA_MOCK" : "LOCAL_RUNTIME");
        result.put("executedAt", Instant.now());
        switch (operation) {
            case "ENABLE" -> enable(account, qaMock, result);
            case "DISABLE" -> disable(account, qaMock, result);
            case "SYNC" -> sync(account, qaMock, result);
            case "RENEW" -> renew(account, qaMock, result);
            default -> throw new IllegalArgumentException("不支持的账号批量操作");
        }
        return result;
    }

    private void enable(XianyuAccount account, boolean qaMock, Map<String, Object> result) {
        account.setStatus(1);
        accounts.updateById(account);
        boolean connected = qaMock || webSocket.startWebSocket(account.getId());
        result.put("accountStatus", 1);
        result.put("connectionAttempted", !qaMock);
        result.put("connected", connected);
        result.put("outcome", connected ? "ENABLED_AND_CONNECTED" : "ENABLED_CONNECTION_PENDING");
        if (!connected) result.put("recoveryHint", "账号已启用，但连接尚未恢复；请在连接管理查看凭据或验证状态");
    }

    private void disable(XianyuAccount account, boolean qaMock, Map<String, Object> result) {
        boolean stopped = qaMock || webSocket.stopWebSocket(account.getId());
        if (!stopped) throw new IllegalStateException("消息连接停止失败，账号未停用");
        account.setStatus(0);
        accounts.updateById(account);
        result.put("accountStatus", 0);
        result.put("connected", false);
        result.put("outcome", "DISABLED");
    }

    private void sync(XianyuAccount account, boolean qaMock, Map<String, Object> result) {
        if (Integer.valueOf(0).equals(account.getStatus())) throw new IllegalStateException("账号已停用，无法同步运行状态");
        boolean connected = qaMock || webSocket.ensureConnected(account.getId());
        result.put("connected", connected);
        result.put("syncScope", "CONNECTION_RUNTIME");
        result.put("outcome", connected ? "RUNTIME_SYNCED" : "RUNTIME_SYNC_FAILED");
        if (!connected) throw new IllegalStateException("连接实况同步失败，请检查凭据或平台验证状态");
    }

    private void renew(XianyuAccount account, boolean qaMock, Map<String, Object> result) {
        if (Integer.valueOf(0).equals(account.getStatus())) throw new IllegalStateException("账号已停用，无法准备续期");
        if (!qaMock) renewal.request(account.getId());
        result.put("outcome", "RENEWAL_PREPARATION_ACCEPTED");
        result.put("delivery", qaMock ? "QA_FIXTURE_ONLY" : "CONFIGURED_PRIVATE_CHANNEL");
        result.put("recoveryHint", qaMock ? "隔离 QA 不生成真实二维码" : "二维码将在企业微信或邮件私密通道送达；需逐账号扫码确认");
    }
}
