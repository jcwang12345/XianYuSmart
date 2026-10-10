package com.xianyusmart.service;

import com.xianyusmart.event.AccountAuthenticatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 登录动作触发的一次性恢复；不是后台轮询，不会扩大到未授权账号。 */
@Slf4j
@Component
public class AccountOnlineRefreshListener {

    private final WebSocketService webSocketService;
    private final ShopProfileSyncService shopProfileSyncService;
    private final ShopRiskSyncService shopRiskSyncService;

    public AccountOnlineRefreshListener(WebSocketService webSocketService,
                                        ShopProfileSyncService shopProfileSyncService,
                                        ShopRiskSyncService shopRiskSyncService) {
        this.webSocketService = webSocketService;
        this.shopProfileSyncService = shopProfileSyncService;
        this.shopRiskSyncService = shopRiskSyncService;
    }

    @Async("taskExecutor")
    @EventListener
    public void onAuthenticated(AccountAuthenticatedEvent event) {
        Long accountId = event.accountId();
        try {
            boolean online = webSocketService.ensureConnected(accountId);
            log.info("登录后账号上线结果: accountId={}, online={}, trigger={}",
                    accountId, online, event.triggerType());
        } catch (Exception e) {
            log.warn("登录后账号上线失败: accountId={}, errorType={}", accountId, e.getClass().getSimpleName());
        }
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try {
            shopProfileSyncService.runBlocking(accountId, "login-profile-" + suffix, event.triggerType());
        } catch (Exception e) {
            log.warn("登录后画像刷新失败: accountId={}, errorType={}", accountId, e.getClass().getSimpleName());
        }
        try {
            shopRiskSyncService.sync(accountId, "login-risk-" + suffix);
        } catch (Exception e) {
            log.warn("登录后处罚刷新失败: accountId={}, errorType={}", accountId, e.getClass().getSimpleName());
        }
    }
}
