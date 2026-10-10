package com.xianyusmart.service.impl;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.CookieRefreshService;
import com.xianyusmart.service.OperationLogService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CredentialFailureClassificationTest {
    @Test void exhaustedUnknownTokenFailureDoesNotExpireCredentialsOrStartLoginRecovery(){
        var service=new WebSocketTokenServiceImpl();var cookies=mock(CookieRefreshService.class);var audits=mock(OperationLogService.class);
        ReflectionTestUtils.setField(service,"cookieRefreshService",cookies);ReflectionTestUtils.setField(service,"operationLogService",audits);
        assertNull(ReflectionTestUtils.invokeMethod(service,"handleTokenFailure",900001L,2,0,null,"network timeout"));verifyNoInteractions(cookies,audits);
    }
    @Test void transportFailureDuringSessionRecoveryStopsWithoutFakeCookieExpiry(){
        var service=new WebSocketTokenServiceImpl();var cookies=mock(CookieRefreshService.class);var audits=mock(OperationLogService.class);
        ReflectionTestUtils.setField(service,"cookieRefreshService",cookies);ReflectionTestUtils.setField(service,"operationLogService",audits);
        when(cookies.refreshCookie(900001L)).thenThrow(new BusinessException(503,"QA unavailable"));
        assertNull(ReflectionTestUtils.invokeMethod(service,"refreshTokenViaHasLogin",900001L,0));verify(cookies).refreshCookie(900001L);verifyNoInteractions(audits);
    }
    @Test void loginRetryExhaustionDistinguishesConfirmedFalseFromTransportFailure(){
        var service=new CookieRefreshServiceImpl();
        assertFalse((Boolean)ReflectionTestUtils.invokeMethod(service,"doCheckLoginStatusWithRetry",900001L,2,false,true));
        var error=assertThrows(BusinessException.class,()->ReflectionTestUtils.invokeMethod(service,"doCheckLoginStatusWithRetry",900001L,2,false,false));assertEquals(503,error.getCode());
    }
    @org.junit.jupiter.api.Test void missingLocalCookieIsUnavailableAndDoesNotExpireAccount() {
        var service=new CookieRefreshServiceImpl();var cookies=org.mockito.Mockito.mock(com.xianyusmart.mapper.XianyuCookieMapper.class);var accounts=org.mockito.Mockito.mock(com.xianyusmart.mapper.XianyuAccountMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"cookieMapper",cookies);org.springframework.test.util.ReflectionTestUtils.setField(service,"accountMapper",accounts);
        var error=org.junit.jupiter.api.Assertions.assertThrows(com.xianyusmart.exception.BusinessException.class,()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"doCheckLoginStatusWithRetry",900001L,0,false));
        org.junit.jupiter.api.Assertions.assertEquals(503,error.getCode());org.mockito.Mockito.verifyNoInteractions(accounts);
    }
    @org.junit.jupiter.api.Test void staleCookieSnapshotCannotClaimRefreshSuccess() {
        var service=new CookieRefreshServiceImpl();var mapper=org.mockito.Mockito.mock(com.xianyusmart.mapper.XianyuCookieMapper.class);org.springframework.test.util.ReflectionTestUtils.setField(service,"cookieMapper",mapper);
        var snapshot=new com.xianyusmart.entity.XianyuCookie();snapshot.setId(900001L);snapshot.setCredentialVersion(7L);
        var update=new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.xianyusmart.entity.XianyuCookie>();
        var error=org.junit.jupiter.api.Assertions.assertThrows(com.xianyusmart.exception.BusinessException.class,()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"commitCookieRefresh",900001L,snapshot,update));
        org.junit.jupiter.api.Assertions.assertEquals(409,error.getCode());org.junit.jupiter.api.Assertions.assertEquals(7L,snapshot.getCredentialVersion());
        org.mockito.Mockito.verify(mapper).update(org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.same(update));
    }
    @org.junit.jupiter.api.Test void mh5tkUnknownFailureAfterConfirmedLoginDoesNotExpireCredentials() {
        var service=new TokenRefreshServiceImpl();var cookies=org.mockito.Mockito.mock(com.xianyusmart.mapper.XianyuCookieMapper.class);var audit=org.mockito.Mockito.mock(com.xianyusmart.service.OperationLogService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"cookieMapper",cookies);org.springframework.test.util.ReflectionTestUtils.setField(service,"operationLogService",audit);
        org.junit.jupiter.api.Assertions.assertEquals(false,org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"handleMh5tkRefreshFailure",900001L,2,true,"unknown transport"));org.mockito.Mockito.verifyNoInteractions(cookies,audit);
    }
    @org.junit.jupiter.api.Test void mh5tkHasLoginUnavailableCannotReachExpiryRetry() {
        var service=new TokenRefreshServiceImpl();var check=org.mockito.Mockito.mock(com.xianyusmart.service.CookieRefreshService.class);var cookies=org.mockito.Mockito.mock(com.xianyusmart.mapper.XianyuCookieMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"cookieRefreshService",check);org.springframework.test.util.ReflectionTestUtils.setField(service,"cookieMapper",cookies);
        org.mockito.Mockito.when(check.checkLoginStatus(900001L)).thenThrow(new com.xianyusmart.exception.BusinessException(503,"unavailable"));
        org.junit.jupiter.api.Assertions.assertEquals(false,org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"refreshMh5tkViaHasLogin",900001L,0));org.mockito.Mockito.verify(check).checkLoginStatus(900001L);org.mockito.Mockito.verifyNoInteractions(cookies);
    }
    @Test void expiredRecoveryWithoutVersionProofCannotTriggerQrRecovery() {
        var service = new WebSocketTokenServiceImpl();
        var checks = mock(CookieRefreshService.class);
        var mapper = mock(com.xianyusmart.mapper.XianyuCookieMapper.class);
        var audits = mock(OperationLogService.class);
        ReflectionTestUtils.setField(service, "cookieRefreshService", checks);
        ReflectionTestUtils.setField(service, "xianyuCookieMapper", mapper);
        ReflectionTestUtils.setField(service, "operationLogService", audits);
        assertNull(ReflectionTestUtils.invokeMethod(service, "refreshTokenViaHasLogin", 900001L, 2));
        verify(mapper, never()).update(any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verifyNoInteractions(audits);
    }
    @Test void invalidVersionProofIsAccountBoundAndSingleUse() {
        var service = new CookieRefreshServiceImpl();
        @SuppressWarnings("unchecked")
        ThreadLocal<long[]> proof = (ThreadLocal<long[]>) ReflectionTestUtils.getField(service, "confirmedInvalidCredential");
        proof.set(new long[]{900001L, 7L});
        assertNull(service.consumeConfirmedInvalidVersion(900002L));
        assertNull(service.consumeConfirmedInvalidVersion(900001L));
        proof.set(new long[]{900001L, 8L});
        assertEquals(8L, service.consumeConfirmedInvalidVersion(900001L));
        assertNull(service.consumeConfirmedInvalidVersion(900001L));
    }
}
