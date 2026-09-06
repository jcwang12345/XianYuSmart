package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuNotificationChannel;
import com.xianyusmart.entity.XianyuNotificationOutbox;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.mapper.XianyuNotificationChannelMapper;
import com.xianyusmart.mapper.XianyuNotificationLogMapper;
import com.xianyusmart.mapper.XianyuNotificationOutboxMapper;
import com.xianyusmart.service.notification.PinnedHttpsClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationCenterServiceOutboxTest {

    @Mock
    private XianyuNotificationChannelMapper channelMapper;
    @Mock
    private XianyuNotificationLogMapper logMapper;
    @Mock
    private XianyuNotificationOutboxMapper outboxMapper;
    @Mock
    private XianyuAccountMapper accountMapper;
    @Mock
    private PinnedHttpsClient httpsClient;

    private NotificationCenterService service;
    private XianyuNotificationChannel channel;

    @BeforeEach
    void setUp() {
        service = new NotificationCenterService(channelMapper, logMapper, outboxMapper,
                accountMapper, httpsClient, new ObjectMapper());
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
        ReflectionTestUtils.setField(service, "leaseSeconds", 60);

        channel = new XianyuNotificationChannel();
        channel.setId(11L);
        channel.setTenantId(7L);
        channel.setChannelType("WECHAT_WORK");
        channel.setChannelName("企业微信");
        channel.setEnabled(1);
        channel.setEventTypes("ORDER_CREATED,DELIVERY_SUCCESS");
        channel.setConfigJson("{\"webhookUrl\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test\"}");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void repeatedOrderCreatedEventsUseTheSameBusinessDedupeKey() {
        TenantContext.set(7L);
        when(channelMapper.selectEnabled()).thenReturn(List.of(channel));

        Map<String, Object> data = Map.of("orderId", "order-1", "xyGoodsId", "goods-1");
        service.dispatch("ORDER_CREATED", 1L, "新订单", "待发货", data);
        service.dispatch("ORDER_CREATED", 1L, "新订单", "待发货", data);

        ArgumentCaptor<XianyuNotificationOutbox> captor =
                ArgumentCaptor.forClass(XianyuNotificationOutbox.class);
        verify(outboxMapper, times(2)).insert(captor.capture());
        List<XianyuNotificationOutbox> tasks = captor.getAllValues();
        assertEquals("account:1:order:order-1", tasks.get(0).getDedupeKey());
        assertEquals(tasks.get(0).getDedupeKey(), tasks.get(1).getDedupeKey());
        assertNotEquals(tasks.get(0).getEventId(), tasks.get(1).getEventId());
    }

    @Test
    void successfulOutboxDeliveryIsMarkedSent() {
        XianyuNotificationOutbox task = task();
        when(outboxMapper.selectDue(50)).thenReturn(List.of(task));
        when(outboxMapper.claim(eq(101L), anyString(), eq(60))).thenReturn(1);
        when(channelMapper.selectById(11L)).thenReturn(channel);
        when(httpsClient.post(anyString(), anyMap(), anyString(), any(Duration.class)))
                .thenReturn(new PinnedHttpsClient.Response(200, "{\"errcode\":0}"));
        when(outboxMapper.markSent(eq(101L), anyString())).thenReturn(1);

        service.dispatchOutbox();

        verify(outboxMapper).markSent(eq(101L), anyString());
        verify(logMapper).insert(any());
    }

    @Test
    void failedOutboxDeliveryIsScheduledForRetry() {
        XianyuNotificationOutbox task = task();
        when(outboxMapper.selectDue(50)).thenReturn(List.of(task));
        when(outboxMapper.claim(eq(101L), anyString(), eq(60))).thenReturn(1);
        when(channelMapper.selectById(11L)).thenReturn(channel);
        when(httpsClient.post(anyString(), anyMap(), anyString(), any(Duration.class)))
                .thenReturn(new PinnedHttpsClient.Response(503, "unavailable"));

        service.dispatchOutbox();

        verify(outboxMapper).retryOrFail(eq(101L), anyString(), eq("RETRY_WAIT"), any(), anyString());
        verify(logMapper).insert(any());
    }

    private XianyuNotificationOutbox task() {
        XianyuNotificationOutbox task = new XianyuNotificationOutbox();
        task.setId(101L);
        task.setTenantId(7L);
        task.setChannelId(11L);
        task.setEventType("ORDER_CREATED");
        task.setXianyuAccountId(1L);
        task.setDedupeKey("account:1:order:order-1");
        task.setEventId("event-1");
        task.setTitle("新订单");
        task.setContent("待发货");
        task.setDataJson("{\"orderId\":\"order-1\"}");
        task.setAttemptCount(0);
        return task;
    }
}
