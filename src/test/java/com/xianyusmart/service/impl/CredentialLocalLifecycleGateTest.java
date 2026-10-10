package com.xianyusmart.service.impl;
import com.xianyusmart.context.*;
import com.xianyusmart.service.*;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.mapper.*;
import com.xianyusmart.controller.dto.QRLoginSession;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class CredentialLocalLifecycleGateTest {
 private JdbcTemplate jdbc;private AccountAutomationPolicy policy;
 @BeforeEach void context(){TenantContext.set(7L);AccountScopeContext.set(false,Set.of(900001L));jdbc=mock(JdbcTemplate.class);when(jdbc.queryForList(anyString(),any(Long.class))).thenReturn(List.of(Map.of("lifecycleState","DISABLED","autoReplyAllowed",1,"autoDeliveryAllowed",1)));when(jdbc.queryForObject(anyString(),eq(Integer.class),anyLong(),anyLong())).thenReturn(1);policy=new AccountAutomationPolicy(jdbc);}
 @AfterEach void clear(){TenantContext.clear();AccountScopeContext.clear();UserContext.clear();}
 @Test void targetQrGenerationPollingAndSaveStopBeforeNetworkOrCredentials(){
  var service=new QRLoginServiceImpl();var accounts=mock(AccountService.class);ReflectionTestUtils.setField(service,"accountService",accounts);ReflectionTestUtils.setField(service,"automation",policy);ReflectionTestUtils.setField(service,"accountAccessService",new AccountAccessService(jdbc));
  var users=mock(SysUserMapper.class);var permissions=mock(PlatformPermissionService.class);
  var user=new com.xianyusmart.entity.SysUser();user.setId(42L);user.setTenantId(7L);user.setStatus(1);user.setAccountScopeMode("ALL");
  when(users.selectById(42L)).thenReturn(user);when(permissions.hasPermission(user,PermissionCatalog.ACTION_CREDENTIAL_WRITE)).thenReturn(true);
  ReflectionTestUtils.setField(service,"authorityUsers",users);ReflectionTestUtils.setField(service,"authorityPermissions",permissions);ReflectionTestUtils.setField(service,"authorityJdbc",jdbc);UserContext.set(42L,"QA operator",7L);
  assertEquals(409,assertThrows(BusinessException.class,()->service.generateQRCode(900001L)).getCode());
  var session=new QRLoginSession("QA-R02-disabled");session.setTenantId(7L);session.setTargetAccountId(900001L);session.setUnb("QA-R02-user");
  session.setOperatorUserId(42L);session.setOriginalAccountIds(Set.of(900001L));
  assertEquals(409,assertThrows(BusinessException.class,()->ReflectionTestUtils.invokeMethod(service,"pollQRCodeStatus",session)).getCode());
  assertEquals(409,assertThrows(BusinessException.class,()->ReflectionTestUtils.invokeMethod(service,"saveCookieToDatabase",session)).getCode());verifyNoInteractions(accounts);
 }
 @Test void existingCookieAndReaddCannotChangeDisabledAccountBeforeGate(){
  var service=new AccountServiceImpl();var accounts=mock(XianyuAccountMapper.class);var cookies=mock(XianyuCookieMapper.class);var account=new XianyuAccount();account.setId(900001L);account.setAccountNote("original");account.setStatus(-2);when(accounts.selectOne(any())).thenReturn(account);
  ReflectionTestUtils.setField(service,"accountMapper",accounts);ReflectionTestUtils.setField(service,"cookieMapper",cookies);ReflectionTestUtils.setField(service,"automation",policy);ReflectionTestUtils.setField(service,"accountAccessService",new AccountAccessService(jdbc));ReflectionTestUtils.setField(service,"lifecycleJdbc",jdbc);
  assertEquals(409,assertThrows(BusinessException.class,()->service.updateAccountCookie(900001L,"QA-R02-user","synthetic-input")).getCode());
  assertEquals(409,assertThrows(BusinessException.class,()->service.saveAccountAndCookie("replacement","QA-R02-user","synthetic-input",null)).getCode());
  assertEquals("original",account.getAccountNote());assertEquals(-2,account.getStatus());verify(accounts,never()).updateById(any());verifyNoInteractions(cookies);
 }
 @Test void reusedMonitorThreadCarriesScopeAndClearsItAfterFailure() throws Exception {
  var service=new QRLoginServiceImpl();var session=new QRLoginSession("QA-context");session.setTenantId(7L);
  UserContext.set(42L,"QA operator",7L);
  Runnable task=ReflectionTestUtils.invokeMethod(service,"monitorTask",session,(Runnable)()->{
   assertEquals(7L,TenantContext.get());assertEquals(42L,UserContext.getUserId());assertEquals(Set.of(900001L),AccountScopeContext.get().accountIds());throw new BusinessException(409,"QA blocked");
  });
  try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()){
   var failed=executor.submit(task);assertThrows(java.util.concurrent.ExecutionException.class,failed::get);
   executor.submit(()->{assertNull(TenantContext.get());assertNull(UserContext.getUserId());assertNull(AccountScopeContext.get());}).get();
  } finally {UserContext.clear();}
 }
}
