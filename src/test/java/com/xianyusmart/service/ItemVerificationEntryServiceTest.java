package com.xianyusmart.service;

import com.xianyusmart.context.UserContext;
import com.xianyusmart.entity.SysUser;
import com.xianyusmart.mapper.SysUserMapper;
import com.xianyusmart.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ItemVerificationEntryServiceTest {
    SysUserMapper users; AccountAccessService access; JdbcTemplate jdbc;
    SysUser user; ItemVerificationEntryService service;
    long now = 100000;
    final String url = "https://h5api.m.goofish.com/_____tmd_____/punish?ticket=SECRET_SENTINEL";
    @BeforeEach void setup() {
        users = mock(SysUserMapper.class); access = mock(AccountAccessService.class); jdbc = mock(JdbcTemplate.class);
        user = new SysUser(); user.setId(1L); user.setTenantId(9L); user.setStatus(1); user.setMemberRole("OWNER");
        when(users.selectOne(any())).thenReturn(user);
        when(access.loadScope(user)).thenReturn(new AccountAccessService.Scope(true, Set.of()));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        // Mockito varargs matching is made explicit for both ownership lookups.
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L), any())).thenReturn(1L);
        service = new ItemVerificationEntryService(users, access, jdbc, new ObjectMapper(), new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(now); }
        });
        UserContext.set(1L, "owner", 9L);
    }
    @AfterEach void cleanup() { UserContext.clear(); }
    void capture(String value) throws Exception {
        service.observe(7L, "123", new ObjectMapper().writeValueAsString(java.util.Map.of(
                "ret", java.util.List.of("FAIL_SYS_USER_VALIDATE::verify"), "data", java.util.Map.of("url", value))));
    }
    @Test void ownerEntryIsMetadataOnlyAndSingleUse() throws Exception {
        capture(url); var meta = service.metadata(7L, "123");
        assertTrue(meta.available()); assertFalse(meta.toString().contains("SECRET_SENTINEL"));
        var consumed = service.consume(7L, "123", meta.entryHandle());
        assertEquals(url, consumed.url()); assertFalse(consumed.toString().contains("SECRET_SENTINEL"));
        assertThrows(BusinessException.class, () -> service.consume(7L, "123", meta.entryHandle()));
        assertFalse(service.metadata(7L, "123").available());
    }
    @Test void freshRoleStatusTenantAndScopeRechecked() throws Exception {
        capture(url); String handle = service.metadata(7L, "123").entryHandle();
        user.setMemberRole("OPERATOR"); assertThrows(BusinessException.class, () -> service.consume(7L, "123", handle));
        user.setMemberRole("OWNER"); user.setStatus(0); assertThrows(BusinessException.class, () -> service.consume(7L, "123", handle));
        user.setStatus(1); user.setTenantId(8L); assertThrows(BusinessException.class, () -> service.consume(7L, "123", handle));
        user.setTenantId(9L); when(access.loadScope(user)).thenReturn(new AccountAccessService.Scope(false, Set.of()));
        assertThrows(BusinessException.class, () -> service.consume(7L, "123", handle));
    }
    @Test void missingContextWrongUserTargetExpiryAndRestartReject() throws Exception {
        capture(url); String handle = service.metadata(7L, "123").entryHandle();
        UserContext.clear(); assertThrows(BusinessException.class, () -> service.consume(7L, "123", handle));
        UserContext.set(2L, "other", 9L); user.setId(2L);
        assertThrows(BusinessException.class, () -> service.consume(7L, "123", handle));
        UserContext.set(1L, "owner", 9L); user.setId(1L);
        assertThrows(BusinessException.class, () -> service.consume(7L, "124", handle));
        now += ItemVerificationEntryService.TTL_MS;
        assertThrows(BusinessException.class, () -> service.consume(7L, "123", handle));
        assertFalse(service.metadata(7L, "123").available());
        assertFalse(new ItemVerificationEntryService(users, access, jdbc, new ObjectMapper()).metadata(7L, "123").available());
    }
    @Test void urlPolicyRejectsSpoofsEncodedPathsAndNestedRedirects() {
        assertNotNull(ItemVerificationEntryService.allowedPath(url));
        assertNotNull(ItemVerificationEntryService.allowedPath("https://h5api.m.goofish.com:443/h5/mtop.taobao.idle.pc.detail/1.0/_____tmd_____/punish?a=opaque"));
        for (String bad : java.util.List.of(
                url.replace("https:", "http:"), url.replace(".com/", ".com.evil/"),
                url.replace("https://", "https://user@"), url.replace(".com/", ".com:8443/"),
                url.replace("punish", "other"), url.replace("punish", "%70unish"),
                url + "#https://evil.test", url + "&next=https%253A%252F%252Fevil.test",
                url + "&returnUrl=%2Felsewhere", url + "&x=https%3A%2F%2Fevil.test",
                url + "&x=%25252525252F%25252525252Fevil.test"))
            assertNull(ItemVerificationEntryService.allowedPath(bad));
    }
    @Test void onlySpecificChallengeCapturedAndSuccessInvalidates() throws Exception {
        capture(url); assertTrue(service.metadata(7L, "123").available());
        service.observe(7L, "123", "{\"ret\":[\"SUCCESS::ok\"],\"data\":{}}");
        assertFalse(service.metadata(7L, "123").available());
        service.observe(7L, "123", "{\"ret\":[\"UNKNOWN::verify\"],\"data\":{}}");
        assertFalse(service.metadata(7L, "123").available());
        capture("https://evil.test/secret"); assertFalse(service.metadata(7L, "123").available());
    }
    @Test void actualAccountOwnershipIsRequired() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L), any())).thenReturn(0L);
        assertThrows(BusinessException.class, () -> service.metadata(7L, "123"));
    }
    @Test void boundedStoreEvictsOldestEntry() throws Exception {
        capture(url); String handle = service.metadata(7L, "123").entryHandle();
        for (int i=1; i<=100; i++) service.observe(7L, Integer.toString(1000+i),
                "{\"ret\":[\"FAIL_SYS_USER_VALIDATE\"],\"data\":{\"url\":\"" + url + "\"}}");
        assertThrows(BusinessException.class, () -> service.consume(7L, "123", handle));
    }
}
