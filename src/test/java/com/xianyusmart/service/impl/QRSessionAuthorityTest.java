package com.xianyusmart.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.*;
import com.xianyusmart.controller.dto.QRLoginSession;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.SysUserMapper;
import com.xianyusmart.service.*;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic responses only: no member permission mutations and no platform sockets. */
class QRSessionAuthorityTest {
    private QRLoginServiceImpl service;
    private JdbcTemplate jdbc;
    private SysUserMapper users;
    private PlatformPermissionService permissions;
    private AccountService accounts;
    private SysUser user;
    private AtomicInteger calls;

    @BeforeEach void setup() {
        TenantContext.set(7L);
        UserContext.set(42L,"QA operator",7L);
        AccountScopeContext.set(true,Set.of());
        jdbc=mock(JdbcTemplate.class);users=mock(SysUserMapper.class);
        permissions=mock(PlatformPermissionService.class);accounts=mock(AccountService.class);
        user=new SysUser();user.setId(42L);user.setTenantId(7L);user.setRole(SysUser.ROLE_USER);
        user.setStatus(1);user.setAccountScopeMode("ALL");
        when(users.selectById(42L)).thenReturn(user);
        when(permissions.hasPermission(user,PermissionCatalog.ACTION_CREDENTIAL_WRITE)).thenReturn(true);
        when(jdbc.queryForObject(anyString(),eq(Integer.class),anyLong(),anyLong())).thenReturn(1);
        when(jdbc.queryForList(anyString(),any(Long.class))).thenReturn(List.of(Map.of("lifecycleState","ACTIVE","autoReplyAllowed",1,"autoDeliveryAllowed",1)));
        when(accounts.getXianyuUserId(1L)).thenReturn("QA-R02-user");
        service=new QRLoginServiceImpl();
        ReflectionTestUtils.setField(service,"authorityJdbc",jdbc);
        ReflectionTestUtils.setField(service,"authorityUsers",users);
        ReflectionTestUtils.setField(service,"authorityPermissions",permissions);
        ReflectionTestUtils.setField(service,"accountAccessService",new AccountAccessService(jdbc));
        ReflectionTestUtils.setField(service,"automation",new AccountAutomationPolicy(jdbc));
        ReflectionTestUtils.setField(service,"accountService",accounts);
        ReflectionTestUtils.setField(service,"eventPublisher",mock(ApplicationEventPublisher.class));
        ReflectionTestUtils.setField(service,"taskExecutor",(java.util.concurrent.Executor)task -> {});
        calls=new AtomicInteger();
        ReflectionTestUtils.setField(service,"httpClient",new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet();String path=chain.request().url().encodedPath();
            String body=path.contains("generate.do") ? "{\"content\":{\"success\":true,\"data\":{\"t\":\"synthetic\",\"ck\":\"synthetic\",\"codeContent\":\"QA only\"}}}"
                    : path.contains("query.do") ? "{\"content\":{\"data\":{\"qrCodeStatus\":\"NEW\"}}}"
                    : path.contains("mini_login.htm") ? "window.viewData = {\"loginFormData\":{\"appName\":\"QA\"}};" : "{}";
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("QA")
                    .body(ResponseBody.create(body,MediaType.get("application/json"))).build();
        }).build());
    }
    @AfterEach void clear(){TenantContext.clear();UserContext.clear();AccountScopeContext.clear();}

    private QRLoginSession session(Long target) {
        var s=new QRLoginSession("QA-R02-authority");s.setTenantId(7L);s.setTargetAccountId(target);
        s.setOperatorUserId(42L);s.setOriginalScopeUnrestricted(true);s.setUnb("QA-R02-user");
        s.getCookies().put("_m_h5_tk","synthetic_only");s.getParams().put("ck","synthetic_only");
        s.setQrContent("synthetic_only");s.setVerificationUrl("https://example.invalid/qa");return s;
    }
    private void assertClean(QRLoginSession s) {
        assertEquals("cancelled",s.getStatus());assertTrue(s.getCookies().isEmpty());assertTrue(s.getParams().isEmpty());
        assertNull(s.getUnb());assertNull(s.getQrContent());assertNull(s.getVerificationUrl());
    }
    static Stream<String> revocations(){return Stream.of("tenant","missing-operator","disabled-user","deleted-user","permission","user-tenant","missing-tenant");}
    @ParameterizedTest @MethodSource("revocations") void pollAndSaveRejectBeforeAnyNetworkOrCredentialWrite(String reason) {
        for(String operation:List.of("pollQRCodeStatus","saveCookieToDatabase")) {
            var s=session(1L);
            switch(reason) {
                case "tenant" -> TenantContext.set(8L);
                case "missing-tenant" -> {TenantContext.clear();s.setTenantId(null);}
                case "missing-operator" -> s.setOperatorUserId(null);
                case "disabled-user" -> user.setStatus(0);
                case "deleted-user" -> when(users.selectById(42L)).thenReturn(null);
                case "permission" -> when(permissions.hasPermission(user,PermissionCatalog.ACTION_CREDENTIAL_WRITE)).thenReturn(false);
                case "user-tenant" -> user.setTenantId(8L);
            }
            assertEquals(403,assertThrows(BusinessException.class,()->ReflectionTestUtils.invokeMethod(service,operation,s)).getCode());
            assertClean(s);
        }
        assertEquals(0,calls.get());verifyNoInteractions(accounts);
    }
    record ScopeCase(boolean originalAll,Set<Long> original,boolean currentAll,Set<Long> current,Set<Long> expected){}
    static Stream<ScopeCase> scopes(){return Stream.of(
            new ScopeCase(true,Set.of(),true,Set.of(),Set.of()),
            new ScopeCase(true,Set.of(),false,Set.of(1L,3L),Set.of(1L,3L)),
            new ScopeCase(false,Set.of(1L,2L),true,Set.of(),Set.of(1L,2L)),
            new ScopeCase(false,Set.of(1L,2L),false,Set.of(1L,3L),Set.of(1L)));}
    @ParameterizedTest @MethodSource("scopes") void scopeIsIntersectionAndLaterExpansionCannotEnlargeOriginal(ScopeCase c) {
        user.setAccountScopeMode(c.currentAll?"ALL":"SELECTED");
        when(jdbc.queryForList(anyString(),eq(Long.class),eq(42L),eq(42L))).thenReturn(new ArrayList<>(c.current));
        var s=session(1L);s.setOriginalScopeUnrestricted(c.originalAll);s.setOriginalAccountIds(c.original);
        ReflectionTestUtils.invokeMethod(service,"requireSessionAuthority",s,1L);
        assertEquals(c.originalAll&&c.currentAll,AccountScopeContext.get().unrestricted());
        assertEquals(c.expected,AccountScopeContext.get().accountIds());
        if(!c.originalAll||!c.currentAll) {
            assertEquals(403,assertThrows(BusinessException.class,()->ReflectionTestUtils.invokeMethod(service,"requireSessionAuthority",s,4L)).getCode());
            assertClean(s);
        }
        assertEquals(0,calls.get());verifyNoInteractions(accounts);
    }
    @Test void scopeRevocationBetweenPollAndCommitBlocksCommit() {
        var s=session(1L);ReflectionTestUtils.invokeMethod(service,"pollQRCodeStatus",s);assertEquals(1,calls.get());
        user.setAccountScopeMode("SELECTED");when(jdbc.queryForList(anyString(),eq(Long.class),eq(42L),eq(42L))).thenReturn(List.of(2L));
        assertEquals(403,assertThrows(BusinessException.class,()->ReflectionTestUtils.invokeMethod(service,"saveCookieToDatabase",s)).getCode());
        assertClean(s);verifyNoInteractions(accounts);assertEquals(1,calls.get());
    }
    @Test void targetlessLoginCannotOverwriteExistingUnbOutsideOriginalScope() {
        var s=session(null);s.setOriginalScopeUnrestricted(false);s.setOriginalAccountIds(Set.of(1L));
        when(jdbc.queryForList(anyString(),eq(Long.class),eq(7L),eq("QA-R02-user"))).thenReturn(List.of(2L));
        assertEquals(403,assertThrows(BusinessException.class,()->ReflectionTestUtils.invokeMethod(service,"saveCookieToDatabase",s)).getCode());
        assertClean(s);verifyNoInteractions(accounts);assertEquals(0,calls.get());
    }
    @Test void targetlessExistingAllowedUnbUsesNormalAccountGatePath() {
        var s=session(null);when(jdbc.queryForList(anyString(),eq(Long.class),eq(7L),eq("QA-R02-user"))).thenReturn(List.of(1L));
        when(accounts.saveAccountAndCookie(anyString(),eq("QA-R02-user"),anyString(),anyString())).thenReturn(1L);
        ReflectionTestUtils.invokeMethod(service,"saveCookieToDatabase",s);assertEquals("success",s.getStatus());assertTrue(s.getCookies().isEmpty());
    }
    @Test void interactiveMissingOperatorCannotFallBackToSystemEvenForDisabledTarget() {
        UserContext.clear();assertEquals(403,assertThrows(BusinessException.class,()->service.generateQRCode(1L)).getCode());
        assertEquals(403,assertThrows(BusinessException.class,()->service.generateQRCode(null)).getCode());assertEquals(0,calls.get());
    }
    @Test void renewalRequiresTenantFixedTargetAndNoInteractiveOperator() {
        assertEquals(403,assertThrows(BusinessException.class,()->service.generateRenewalQRCode(1L)).getCode());
        UserContext.clear();assertEquals(403,assertThrows(BusinessException.class,()->service.generateRenewalQRCode(null)).getCode());
        TenantContext.clear();assertEquals(403,assertThrows(BusinessException.class,()->service.generateRenewalQRCode(1L)).getCode());assertEquals(0,calls.get());
    }
    @Test void renewalSuccessRestoresCallingScopeAndCommitsOnlyFixedTarget() {
        UserContext.clear();AccountScopeContext.set(false,Set.of(99L));var before=AccountScopeContext.get();
        var result=service.generateRenewalQRCode(1L);assertTrue(result.isSuccess());assertEquals(before,AccountScopeContext.get());
        var s=service.ownedSession(result.getSessionId());assertTrue(s.isTrustedRenewal());assertNull(s.getOperatorUserId());
        s.setUnb("QA-R02-user");s.getCookies().put("unb","QA-R02-user");
        when(accounts.updateAccountCookie(eq(1L),eq("QA-R02-user"),anyString())).thenReturn(true);
        ReflectionTestUtils.invokeMethod(service,"saveCookieToDatabase",s);assertEquals("success",s.getStatus());assertEquals(1L,s.getAccountId());
        assertTrue(s.getCookies().isEmpty());verify(accounts).updateAccountCookie(eq(1L),eq("QA-R02-user"),anyString());
        verify(accounts,never()).saveAccountAndCookie(any(),any(),any(),any());
    }
    @Test void renewalDisabledTargetRestoresCallingScopeWithoutNetwork() {
        UserContext.clear();AccountScopeContext.set(false,Set.of(99L));var before=AccountScopeContext.get();
        when(jdbc.queryForList(anyString(),any(Long.class))).thenReturn(List.of(Map.of("lifecycleState","DISABLED","autoReplyAllowed",1,"autoDeliveryAllowed",1)));
        assertEquals(409,assertThrows(BusinessException.class,()->service.generateRenewalQRCode(1L)).getCode());
        assertEquals(before,AccountScopeContext.get());assertEquals(0,calls.get());verifyNoInteractions(accounts);
    }
    @Test void rejectedMonitorSubmissionRemovesSessionAndClearsPreparedQrSecrets() {
        var s=session(1L);
        ReflectionTestUtils.setField(service,"taskExecutor",(java.util.concurrent.Executor)task -> {
            throw new java.util.concurrent.RejectedExecutionException("synthetic secret must not escape");
        });
        var result=(com.xianyusmart.controller.dto.QRLoginResponse)ReflectionTestUtils.invokeMethod(service,"generateQRCodeAllowed",s);
        assertFalse(result.isSuccess());assertFalse(result.getMessage().contains("secret"));
        assertNull(service.ownedSession(s.getSessionId()));
        assertEquals("error",s.getStatus());assertTrue(s.getCookies().isEmpty());assertTrue(s.getParams().isEmpty());
        assertNull(s.getUnb());assertNull(s.getQrContent());assertNull(s.getQrCodeUrl());
    }
    @Test void authorityCancellationAndSaveErrorKeepTheirTerminalStateAfterScanDeadline() {
        for(String status:List.of("cancelled","error")) {
            var s=session(1L);s.setStatus(status);s.setCreatedTime(System.currentTimeMillis()-s.getExpireTime()-1000);
            ((java.util.Map<String,QRLoginSession>)ReflectionTestUtils.getField(service,"sessions")).put(s.getSessionId(),s);
            assertEquals(status,service.getSessionStatus(s.getSessionId()).getStatus());assertEquals(status,s.getStatus());
        }
        assertEquals(0,calls.get());verifyNoInteractions(accounts);
    }
    @Test void generationSetupFailureClearsSensitiveSessionAndRestoresRenewalScope() {
        UserContext.clear();AccountScopeContext.set(false,Set.of(99L));var before=AccountScopeContext.get();
        ReflectionTestUtils.setField(service,"httpClient",new OkHttpClient.Builder().addInterceptor(chain -> {
            throw new java.io.IOException("synthetic sensitive response must not escape");
        }).build());
        var result=service.generateRenewalQRCode(1L);assertFalse(result.isSuccess());assertEquals(before,AccountScopeContext.get());
        assertFalse(result.getMessage().contains("sensitive"));
        var s=session(1L);var failed=ReflectionTestUtils.invokeMethod(service,"generateQRCodeAllowed",s);
        assertNotNull(failed);assertEquals("error",s.getStatus());assertTrue(s.getCookies().isEmpty());assertTrue(s.getParams().isEmpty());assertNull(s.getUnb());
    }
    @Test void renewalMonitorReusedThreadClearsAuthorityAfterTenantRejection() throws Exception {
        UserContext.clear();var s=session(1L);s.setOperatorUserId(null);s.setTrustedRenewal(true);
        Runnable task=ReflectionTestUtils.invokeMethod(service,"monitorTask",s,(Runnable)()->{
            TenantContext.set(8L);ReflectionTestUtils.invokeMethod(service,"pollQRCodeStatus",s);
        });
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            assertThrows(java.util.concurrent.ExecutionException.class,()->executor.submit(task).get());
            executor.submit(()->{assertNull(TenantContext.get());assertNull(UserContext.getUserId());assertNull(AccountScopeContext.get());}).get();
        }
        assertClean(s);assertEquals(0,calls.get());verifyNoInteractions(accounts);
    }
    @Test void renewalOwnershipChangeAndAccountSwitchAreRejectedAndCleaned() {
        var s=session(1L);s.setTrustedRenewal(true);s.setOperatorUserId(null);
        assertEquals(403,assertThrows(BusinessException.class,()->ReflectionTestUtils.invokeMethod(service,"requireSessionAuthority",s,2L)).getCode());assertClean(s);
        var changed=session(1L);changed.setTrustedRenewal(true);changed.setOperatorUserId(null);
        when(jdbc.queryForObject(anyString(),eq(Integer.class),anyLong(),anyLong())).thenReturn(0);
        assertEquals(403,assertThrows(BusinessException.class,()->ReflectionTestUtils.invokeMethod(service,"pollQRCodeStatus",changed)).getCode());assertClean(changed);
        assertEquals(0,calls.get());verifyNoInteractions(accounts);
    }
    @Test void internalAuthorityMetadataIsAbsentFromJson() throws Exception {
        var s=session(1L);s.setTrustedRenewal(true);s.setOriginalAccountIds(Set.of(1L));
        var json=new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(s));
        for(String key:List.of("tenantId","operatorUserId","originalScopeUnrestricted","originalAccountIds","trustedRenewal")) assertFalse(json.has(key),key);
    }
}
