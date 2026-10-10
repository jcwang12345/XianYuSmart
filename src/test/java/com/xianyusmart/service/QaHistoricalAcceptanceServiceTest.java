package com.xianyusmart.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.*;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.interceptor.AccessControlInterceptor;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.mapper.SysUserMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class QaHistoricalAcceptanceServiceTest {
    JdbcTemplate jdbc;org.springframework.transaction.PlatformTransactionManager manager;AccountAccessService access;QaHistoricalAcceptanceService qa;final ObjectMapper json=new ObjectMapper();
    SysUserMapper users;PlatformPermissionService permissions;ProductEventService events;
    final String signingKey="synthetic-local-qa-key-32-characters-only";
    @BeforeEach void init(){TenantContext.set(1L);UserContext.set(42L,"QA",1L);jdbc=mock(JdbcTemplate.class);manager=mock(org.springframework.transaction.PlatformTransactionManager.class);when(manager.getTransaction(any())).thenAnswer(ignored->new org.springframework.transaction.support.SimpleTransactionStatus());access=mock(AccountAccessService.class);var env=new MockEnvironment();env.setActiveProfiles("qa");
        users=mock(SysUserMapper.class);permissions=mock(PlatformPermissionService.class);events=mock(ProductEventService.class);
        var actor=new SysUser();actor.setId(42L);actor.setTenantId(1L);actor.setStatus(1);actor.setMemberRole("OWNER");actor.setRole("USER");
        when(users.selectById(42L)).thenReturn(actor);when(permissions.hasPermission(any(SysUser.class),anyString())).thenReturn(true);
        when(access.loadScope(any())).thenReturn(new AccountAccessService.Scope(true,Set.of()));
        qa=new QaHistoricalAcceptanceService(jdbc,access,json,manager,env,1,"99",events,new ProductEventSafety(json),users,permissions,signingKey);
        when(jdbc.queryForList(anyString(),eq(1L),eq(99L))).thenReturn(List.of(Map.of("account_note","QA-R03-99","unb","QA-R03-99")));when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(99L))).thenReturn(0);}
    @AfterEach void clear(){TenantContext.clear();UserContext.clear();AccountScopeContext.clear();}
    MockHttpServletRequest request(String method,String uri){var r=new MockHttpServletRequest(method,uri);r.addHeader("Authorization","Bearer synthetic-test-only");r.setContentType("application/json");return r;}
    MockHttpServletRequest query(){var r=request("POST","/api/product-matrix/products/query");r.addHeader("X-QA-R03-Request-Id","QA-R03-fault");r.setContent("{\"accountIds\":[99],\"search\":\"QA\"}".getBytes());return r;}
    void fault(String mode,int calls,int delay){qa.configure(99L,"QA-R03-fault","PRODUCT_LIST",mode,calls,delay,request("POST","/api/qa/r03/faults"));}
    @Test void fixtureWritesUseExplicitTransactionAndSqlFailureRollsBack() {
        assertEquals(false,qa.fixtures(99L,"QA-R03-local",2).get("automaticConfigurationCreated"));
        verify(manager).commit(any());
        doThrow(new org.springframework.dao.DataIntegrityViolationException("QA injected fixture failure")).when(jdbc).update(anyString(),any(Object[].class));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->qa.fixtures(99L,"QA-R03-fail",1));
        verify(manager).rollback(any());
    }
    @Test void defaultDisabledOrNonQaProfileDoesNotRegisterAnyFaultFilter(){
        for(boolean production:List.of(false,true))try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(production?"production":"qa");
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("qa-test",Map.of("app.r03.qa.enabled",production?"true":"false")));
            context.register(QaHistoricalAcceptanceService.class);context.refresh();
            assertEquals(0,context.getBeanNamesForType(QaHistoricalAcceptanceService.class).length);
        }
    }
    @Test void implicitSessionFaultAffectsOnlyTheRegisteredAccountRead()throws Exception {
        fault("FAIL",2,0);var targeted=query();targeted.removeHeader("X-QA-R03-Request-Id");var response=new MockHttpServletResponse();
        qa.doFilter(targeted,response,(r,s)->s.getWriter().write("{\"code\":200,\"data\":{}}"));assertEquals(503,response.getStatus());assertEquals("QA-R03-fault",response.getHeader("X-QA-R03-Request-Id"));
        var other=query();other.removeHeader("X-QA-R03-Request-Id");other.setContent("{\"accountIds\":[100]}".getBytes());response=new MockHttpServletResponse();
        qa.doFilter(other,response,(r,s)->{assertEquals("{\"accountIds\":[100]}",new String(r.getInputStream().readAllBytes()));s.getWriter().write("{\"code\":200,\"data\":{}}" );});assertEquals(200,response.getStatus());assertEquals(1,qa.evidence("QA-R03-fault",query()).get("applied"));
    }
    @Test void actualAccountScopeDenialPreventsFaultRegistration(){doThrow(new BusinessException(403,"QA scope denied")).when(access).requireAccess(99L);assertEquals(403,assertThrows(BusinessException.class,()->fault("FAIL",1,0)).getCode());}
    @Test void registrationNeedsExplicitQaTenantAndCredentialsNeverFallBack(){assertThrows(IllegalStateException.class,()->new QaHistoricalAcceptanceService(jdbc,access,json,manager,new MockEnvironment(),1,"99",events,new ProductEventSafety(json),users,permissions,signingKey));when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(99L))).thenReturn(1);assertEquals(403,assertThrows(BusinessException.class,()->fault("FAIL",2,0)).getCode());verifyNoInteractions(access);}

    String signature(long timestamp,String event,String goods,Map<String,Object> after)throws Exception {
        var payload=new LinkedHashMap<String,Object>();payload.put("version",1);payload.put("timestamp",timestamp);payload.put("tenantId",1L);
        payload.put("accountId",99L);payload.put("eventId",event);payload.put("goodsId",goods);payload.put("occurredAt","2026-10-02T08:00:00Z");payload.put("before",Map.of());payload.put("after",after);
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(signingKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(new ProductEventSafety(json).canonical(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Test void validSignatureCallsTheRealBusinessServiceWithFixedInputs()throws Exception {
        long now=System.currentTimeMillis();var after=Map.<String,Object>of("title","safe QA title");
        when(events.observeQaProduct(any(),anyString(),anyString(),anyString(),anyMap(),anyMap())).thenReturn(Map.of("replayed",false));
        assertEquals(false,qa.signedEvent(99L,"QA-R03-event","QA-R03-goods","2026-10-02T08:00:00Z",1L,now,signature(now,"QA-R03-event","QA-R03-goods",after),Map.of(),after).get("replayed"));
        verify(events).observeQaProduct(99L,"QA-R03-goods","QA-R03-event","2026-10-02T08:00:00Z",Map.of(),after);
    }
    @Test void missingTamperedExpiredAndCrossTenantSignaturesNeverCallPersistence()throws Exception {
        long now=System.currentTimeMillis();var safe=Map.<String,Object>of("title","safe");String signature=signature(now,"QA-R03-event","QA-R03-goods",safe);
        for(var call:List.<org.junit.jupiter.api.function.Executable>of(
                ()->qa.signedEvent(99L,"QA-R03-event","QA-R03-goods","2026-10-02T08:00:00Z",1L,now,null,Map.of(),safe),
                ()->qa.signedEvent(99L,"QA-R03-event","QA-R03-goods","2026-10-02T08:00:00Z",1L,now,signature,Map.of(),Map.of("title","changed")),
                ()->qa.signedEvent(99L,"QA-R03-event","QA-R03-goods","2026-10-02T08:00:00Z",1L,now-301000,signature,Map.of(),safe),
                ()->qa.signedEvent(99L,"QA-R03-event","QA-R03-goods","2026-10-02T08:00:00Z",2L,now,signature,Map.of(),safe)))
            assertEquals(403,assertThrows(BusinessException.class,call).getCode());
        verifyNoInteractions(events);
    }
    @Test void systemWritePermissionCannotPromoteAnOperatorIntoQaManagement(){
        users.selectById(42L).setMemberRole("OPERATOR");assertEquals(403,assertThrows(BusinessException.class,()->qa.fixtures(99L,"QA-R03-x",1)).getCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void platformAdminMustStillBelongToCurrentTenant(){users.selectById(42L).setRole("ADMIN");users.selectById(42L).setTenantId(2L);assertEquals(403,assertThrows(BusinessException.class,()->fault("FAIL",1,0)).getCode());}
    @Test void invalidTenantOperatorAccountAndPrefixRejectBeforeFixturesWrite(){TenantContext.set(2L);assertThrows(BusinessException.class,()->qa.fixtures(99L,"QA-R03-x",1));TenantContext.set(1L);UserContext.clear();assertThrows(BusinessException.class,()->qa.fixtures(99L,"QA-R03-x",1));UserContext.set(42L,"QA",1L);assertThrows(BusinessException.class,()->qa.fixtures(100L,"QA-R03-x",1));when(jdbc.queryForList(anyString(),eq(1L),eq(99L))).thenReturn(List.of(Map.of("account_note","real","unb","real")));assertThrows(BusinessException.class,()->qa.fixtures(99L,"QA-R03-x",1));verify(jdbc,never()).update(anyString(),any(Object[].class));}
    @Test void fixtureAndFaultBoundsRejectArbitraryUrlAndUnboundedWork(){assertThrows(BusinessException.class,()->qa.fixtures(99L,"QA-R03-x",1001));assertThrows(BusinessException.class,()->qa.configure(99L,"QA-R03-fault","https://example.invalid","FAIL",2,0,query()));assertThrows(BusinessException.class,()->qa.configure(99L,"QA-R03-fault","PRODUCT_LIST","FAIL",21,0,query()));assertThrows(BusinessException.class,()->qa.configure(99L,"QA-R03-fault","PRODUCT_LIST","SLOW",2,3001,query()));}
    @Test void callerCannotOverwriteOrReadAnotherSessionFault(){fault("FAIL",2,0);UserContext.set(43L,"other",1L);assertEquals(403,assertThrows(BusinessException.class,()->fault("FAIL",2,0)).getCode());assertEquals(403,assertThrows(BusinessException.class,()->qa.evidence("QA-R03-fault",query())).getCode());}
    @Test void boundedFailureUsesActualAuthorizedReadAndPreservesBody()throws Exception{fault("FAIL",2,0);var called=new AtomicInteger();for(int i=0;i<3;i++){var response=new MockHttpServletResponse();qa.doFilter(query(),response,(r,s)->{called.incrementAndGet();assertEquals("{\"accountIds\":[99],\"search\":\"QA\"}",new String(r.getInputStream().readAllBytes()));s.setContentType("application/json");s.getWriter().write("{\"code\":200,\"data\":{\"total\":1000}}");});assertEquals(i<2?503:200,response.getStatus());assertEquals(i<2?503:200,json.readTree(response.getContentAsString()).get("code").asInt());}assertEquals(3,called.get());assertEquals(2,qa.evidence("QA-R03-fault",query()).get("applied"));}
    @Test void slowFixtureDelaysOnlySuccessfulRead()throws Exception{fault("SLOW",1,15);long start=System.nanoTime();var response=new MockHttpServletResponse();qa.doFilter(query(),response,(r,s)->s.getWriter().write("{\"code\":200,\"data\":{}}"));assertTrue(System.nanoTime()-start>=10_000_000);assertEquals(200,response.getStatus());assertEquals(1,qa.evidence("QA-R03-fault",query()).get("applied"));}
    @Test void permissionDenialCannotBeReplacedOrConsumeFault()throws Exception{fault("FAIL",2,0);var response=new MockHttpServletResponse();qa.doFilter(query(),response,(r,s)->{((jakarta.servlet.http.HttpServletResponse)s).setStatus(403);s.getWriter().write("{\"code\":403,\"message\":\"denied\"}");});assertEquals(403,response.getStatus());assertTrue(response.getContentAsString().contains("denied"));assertEquals(2,qa.evidence("QA-R03-fault",query()).get("remaining"));}
    @Test void wrongTokenWrongAccountAndWriteRouteNeverReachChain()throws Exception{fault("FAIL",2,0);var requests=new ArrayList<MockHttpServletRequest>();var token=query();token.removeHeader("Authorization");token.addHeader("Authorization","Bearer wrong-synthetic");requests.add(token);var account=query();account.setContent("{\"accountIds\":[100]}".getBytes());requests.add(account);var write=query();write.setRequestURI("/api/product-matrix/batches");requests.add(write);for(var r:requests){var response=new MockHttpServletResponse();qa.doFilter(r,response,(a,b)->fail("unsafe request reached business route"));assertEquals(403,response.getStatus());}}
    @Test void addedCredentialsAfterFaultInstallRejectWithoutRealRead()throws Exception{fault("FAIL",2,0);when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(99L))).thenReturn(1);var response=new MockHttpServletResponse();qa.doFilter(query(),response,(r,s)->fail("invalid QA object fell back"));assertEquals(403,response.getStatus());}
    @Test void resetStopsInjectionAndEvidenceDoesNotContainToken()throws Exception{fault("FAIL",2,0);qa.configure(99L,"QA-R03-fault","PRODUCT_LIST","RESET",1,0,query());var response=new MockHttpServletResponse();qa.doFilter(query(),response,(r,s)->fail("reset header should be rejected, caller must remove it"));assertEquals(410,response.getStatus());assertFalse(json.writeValueAsString(qa.evidence("QA-R03-fault",query())).contains("synthetic-test-only"));}
    @Test void routesRequireMenuReadAndExplicitSystemWriteForFixtureAndFault(){var acl=new AccessControlInterceptor(mock(PlatformPermissionService.class));assertEquals(PermissionCatalog.MENU_GOODS,ReflectionTestUtils.invokeMethod(acl,"resolveMenuPermission","/api/qa/r03/evidence/QA-R03-x"));for(String route:List.of("fixtures","faults"))assertEquals(PermissionCatalog.ACTION_SYSTEM_WRITE,ReflectionTestUtils.invokeMethod(acl,"resolveActionPermission","POST","/api/qa/r03/"+route));assertNull(ReflectionTestUtils.invokeMethod(acl,"resolveActionPermission","GET","/api/qa/r03/evidence/QA-R03-x"));}
}
