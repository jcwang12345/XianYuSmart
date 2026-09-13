package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.controller.dto.*;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.service.notification.RenewalImageStore;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import java.util.Base64;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class CredentialRenewalServiceTest {
    static { System.setProperty("java.awt.headless","true"); }
    static String png() throws Exception {
        var image=new java.awt.image.BufferedImage(20,20,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
        return "data:image/png;base64,"+Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    @AfterEach void clear(){TenantContext.clear();}
    @Test void loginImageIsTenantAccountBoundAndExpires() throws Exception {
        var images=new RenewalImageStore();String ref=images.put(7L,1L,png(),System.currentTimeMillis()+60000);
        assertNotNull(images.get(ref,7L,1L));assertNull(images.get(ref,8L,1L));assertNull(images.get(ref,7L,2L));
        images.remove(ref);assertNull(images.get(ref,7L,1L));
        String expired=images.put(7L,1L,png(),System.currentTimeMillis()-1);assertNull(images.get(expired,7L,1L));
    }
    @Test void invalidImageIsNotAccepted() {
        assertThrows(IllegalArgumentException.class,()->new RenewalImageStore().put(7L,1L,"https://example.com/qr",Long.MAX_VALUE));
    }
    @Test @SuppressWarnings("unchecked") void duplicateExpiryGeneratesOnlyOneBoundQrAndNoPrematureRecovery() throws Exception {
        var accounts=mock(XianyuAccountMapper.class);var qr=mock(QRLoginService.class);var mail=mock(EmailNotifyService.class);
        var ws=mock(WebSocketService.class);var notices=mock(NotificationCenterService.class);
        ObjectProvider<WebSocketService> wsp=mock(ObjectProvider.class);when(wsp.getObject()).thenReturn(ws);
        ObjectProvider<EmailNotifyService> mp=mock(ObjectProvider.class);when(mp.getObject()).thenReturn(mail);
        var account=new XianyuAccount();account.setId(1L);account.setTenantId(7L);account.setStatus(1);when(accounts.selectById(1L)).thenReturn(account);
        when(mail.isEmailConfigured()).thenReturn(true);when(mail.isCookieExpireNotifyEnabled()).thenReturn(true);
        when(qr.generateQRCode(1L)).thenReturn(new QRLoginResponse(true,"session",png(),"ok"));
        var service=new CredentialRenewalService(accounts,qr,wsp,mp,notices,new RenewalImageStore(),Runnable::run);
        TenantContext.set(7L);service.request(1L);TenantContext.set(7L);service.request(1L);
        verify(qr,times(1)).generateQRCode(1L);
        verify(mail).sendCredentialRenewalMail(contains("需扫码续期"),contains("无需登录管理网页"),any(byte[].class));
        verify(notices,never()).dispatch(eq("ACCOUNT_RECOVERED"),any(),any(),any(),any());
        verifyNoInteractions(ws);
        var status=new QRStatusResponse();status.setStatus("confirmed");status.setAccountId(1L);
        when(qr.getSessionStatus("session")).thenReturn(status);
        var flows=(java.util.Map<Long,Object>)org.springframework.test.util.ReflectionTestUtils.getField(service,"flows");
        org.springframework.test.util.ReflectionTestUtils.setField(flows.get(1L),"nextCheck",0L);
        service.checkFlows();
        verify(ws).restartAfterCredentialUpdate(1L);
        verify(notices,never()).dispatch(eq("ACCOUNT_RECOVERED"),any(),any(),any(),any());
        when(ws.isConnected(1L)).thenReturn(true);
        org.springframework.test.util.ReflectionTestUtils.setField(flows.get(1L),"nextCheck",0L);
        service.checkFlows();
        verify(notices).dispatch(eq("ACCOUNT_RECOVERED"),eq(1L),anyString(),contains("消息监听已恢复"),anyMap());
    }
    @Test @SuppressWarnings("unchecked") void disabledAccountNeverGeneratesQr() {
        var accounts=mock(XianyuAccountMapper.class);var qr=mock(QRLoginService.class);
        var account=new XianyuAccount();account.setId(1L);account.setTenantId(7L);account.setStatus(0);when(accounts.selectById(1L)).thenReturn(account);
        var service=new CredentialRenewalService(accounts,qr,mock(ObjectProvider.class),mock(ObjectProvider.class),mock(NotificationCenterService.class),new RenewalImageStore(),Runnable::run);
        service.request(1L);verifyNoInteractions(qr);
    }
}
