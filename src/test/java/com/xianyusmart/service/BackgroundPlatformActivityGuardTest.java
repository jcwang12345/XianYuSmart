package com.xianyusmart.service;

import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuGoodsOrderMapper;
import com.xianyusmart.mapper.XianyuKamiItemMapper;
import com.xianyusmart.service.impl.TokenRefreshServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;

class BackgroundPlatformActivityGuardTest {

    private BackgroundPlatformActivityPolicy disabled() {
        BackgroundPlatformActivityPolicy policy = new BackgroundPlatformActivityPolicy();
        ReflectionTestUtils.setField(policy, "enabled", false);
        return policy;
    }

    @Test
    void deliveryDispatchAndSoldOrderDiscoveryAreBothBlocked() {
        DeliveryTaskService tasks = mock(DeliveryTaskService.class);
        XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
        DeliveryTaskScheduler scheduler = new DeliveryTaskScheduler(tasks, mock(AutoDeliveryService.class),
                mock(XianyuGoodsOrderMapper.class), mock(XianyuKamiItemMapper.class), accounts,
                mock(OrderService.class), mock(PendingOrderPollService.class), mock(WebSocketService.class),
                mock(BuyerProfileService.class), Runnable::run);
        ReflectionTestUtils.setField(scheduler, "backgroundPlatformActivityPolicy", disabled());

        scheduler.dispatchDueTasks();
        scheduler.discoverOrdersFromApi();

        verifyNoInteractions(tasks, accounts);
    }

    @Test
    void cookieKeepAliveIsBlockedBeforeReadingOrUpdatingCredentials() {
        TokenRefreshServiceImpl service = new TokenRefreshServiceImpl();
        XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
        ReflectionTestUtils.setField(service, "accountMapper", accounts);
        ReflectionTestUtils.setField(service, "backgroundPlatformActivityPolicy", disabled());

        service.scheduledCookieKeepAlive();

        verifyNoInteractions(accounts);
    }

    @Test
    void websocketRecoveryIsBlockedBeforeAccountDiscovery() {
        XianyuAccountMapper accounts = mock(XianyuAccountMapper.class);
        WebSocketRecoveryScheduler scheduler = new WebSocketRecoveryScheduler(
                accounts, mock(WebSocketService.class), Runnable::run);
        ReflectionTestUtils.setField(scheduler, "backgroundPlatformActivityPolicy", disabled());

        scheduler.maintainConnections();

        verifyNoInteractions(accounts);
    }
}
