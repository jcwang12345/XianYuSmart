package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.controller.dto.*;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.service.notification.RenewalImageStore;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CredentialRenewalWindowTest {
    private QRLoginService qr;
    private NotificationCenterService notices;
    private RenewalImageStore images;
    private CredentialRenewalService service;
    private Object flow;
    private long deadline;

    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        var accounts=mock(XianyuAccountMapper.class);qr=mock(QRLoginService.class);
        var mail=mock(EmailNotifyService.class);notices=mock(NotificationCenterService.class);
        ObjectProvider<WebSocketService> wp=mock(ObjectProvider.class);
        when(wp.getObject()).thenReturn(mock(WebSocketService.class));
        ObjectProvider<EmailNotifyService> mp=mock(ObjectProvider.class);when(mp.getObject()).thenReturn(mail);
        var account=new XianyuAccount();account.setId(2L);account.setTenantId(7L);account.setStatus(1);account.setAccountNote("数码配件二店");
        when(accounts.selectById(2L)).thenReturn(account);
        when(notices.hasRenewalImageChannel()).thenReturn(true);
        deadline=System.currentTimeMillis()+QRLoginSession.MAX_WAIT_MS;
        when(qr.generateQRCode(2L)).thenReturn(new QRLoginResponse(true,"session",CredentialRenewalServiceTest.png(),null,deadline));
        images=spy(new RenewalImageStore());
        service=new CredentialRenewalService(accounts,qr,wp,mp,notices,images,Runnable::run);
        TenantContext.set(7L);service.request(2L);
        flow=((Map<Long,Object>)ReflectionTestUtils.getField(service,"flows")).get(2L);
    }
    @AfterEach void cleanup(){TenantContext.clear();}
    void poll(String value) {
        var status=new QRStatusResponse();status.setStatus(value);
        when(qr.getSessionStatus("session")).thenReturn(status);
        ReflectionTestUtils.setField(flow,"nextCheck",0L);service.checkFlows();
    }
    @Test void usesSessionDeadlineAndRendersAccountNote() {
        assertEquals(deadline,ReflectionTestUtils.getField(flow,"expiresAt"));
        verify(images).put(eq(7L),eq(2L),eq("数码配件二店"),anyString(),eq(deadline));
        verify(notices).dispatch(eq("CREDENTIAL_EXPIRED"),eq(2L),contains("需扫码续期"),contains("30 分钟窗口"),anyMap());
    }
    @Test void platformExpiryReplacesImageBeforeLocalDeadline() {
        String old=(String)ReflectionTestUtils.getField(flow,"image");
        poll("expired");verify(qr,times(2)).generateQRCode(2L);
        assertNull(images.get(old,7L,2L));
    }
    @Test void scannedConfirmationIsNotInterruptedAtImageCutoff() {
        ReflectionTestUtils.setField(flow,"expiresAt",0L);poll("scanned");
        verify(qr,times(1)).generateQRCode(2L);
        assertEquals(false,ReflectionTestUtils.getField(flow,"terminal"));
    }
    @Test void thirtyMinuteWindowStopsReplacement() {
        ReflectionTestUtils.setField(flow,"windowEndsAt",0L);poll("expired");
        verify(qr,times(1)).generateQRCode(2L);
        assertEquals(true,ReflectionTestUtils.getField(flow,"terminal"));
    }
    @Test void excessivePlatformExpiryHasTenImageLimit() {
        ReflectionTestUtils.setField(flow,"generation",10);poll("expired");
        verify(qr,times(1)).generateQRCode(2L);
        assertEquals(true,ReflectionTestUtils.getField(flow,"terminal"));
    }
    @Test void missingSessionDeadlineNeverProducesMisleadingImage() {
        when(qr.generateQRCode(2L)).thenReturn(new QRLoginResponse(true,"unknown-deadline", "invalid",null));
        poll("expired");
        assertEquals(true,ReflectionTestUtils.getField(flow,"terminal"));
        verify(images,times(1)).put(anyLong(),anyLong(),anyString(),anyString(),anyLong());
    }
}
