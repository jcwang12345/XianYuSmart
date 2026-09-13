package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.mapper.XianyuAccountMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * WebSocket 连接自愈任务
 */
@Slf4j
@Component
public class WebSocketRecoveryScheduler {

    private final XianyuAccountMapper accountMapper;
    private final WebSocketService webSocketService;
    private final Executor taskExecutor;
    private final Set<Long> connectingAccounts = ConcurrentHashMap.newKeySet();
    private final java.util.Map<Long, Long> nextAttempt = new ConcurrentHashMap<>();
    private final java.util.Map<Long, Integer> failures = new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    private WebSocketTokenService tokenService;

    public WebSocketRecoveryScheduler(XianyuAccountMapper accountMapper,
                                      WebSocketService webSocketService,
                                      @Qualifier("taskExecutor") Executor taskExecutor) {
        this.accountMapper = accountMapper;
        this.webSocketService = webSocketService;
        this.taskExecutor = taskExecutor;
    }

    @Scheduled(fixedDelayString = "${websocket.recovery-check-interval-ms:10000}", initialDelay = 3000)
    public void maintainConnections() {
        // 仅检查凭证有效的启用账号，避免无效账号产生重复连接和无意义日志。
        for (XianyuAccount account : accountMapper.selectReconnectableAccounts()) {
            if (webSocketService.isConnected(account.getId())) {
                nextAttempt.remove(account.getId()); failures.remove(account.getId()); continue;
            }
            if(tokenService.getPendingCaptchaUrl(account.getId())!=null
                    || nextAttempt.getOrDefault(account.getId(),0L)>System.currentTimeMillis()) continue;
            if (webSocketService.isConnected(account.getId())
                    || !connectingAccounts.add(account.getId())) {
                continue;
            }
            taskExecutor.execute(() -> connect(account));
        }
    }

    private void connect(XianyuAccount account) {
        try {
            TenantContext.set(account.getTenantId());
            if (webSocketService.ensureConnected(account.getId())) {
                nextAttempt.remove(account.getId()); failures.remove(account.getId());
            } else {
                backoff(account.getId());
            }
        } catch (Exception e) {
            backoff(account.getId());
            log.warn("【账号{}】连接自愈失败: {}", account.getId(), e.getMessage());
        } finally {
            TenantContext.clear();
            connectingAccounts.remove(account.getId());
        }
    }
    private void backoff(Long id) {
        int count=failures.merge(id,1,Integer::sum);
        nextAttempt.put(id,System.currentTimeMillis()+(count==1?300:count==2?900:1800)*1000L
                +java.util.concurrent.ThreadLocalRandom.current().nextLong(5000,30000));
    }
}
