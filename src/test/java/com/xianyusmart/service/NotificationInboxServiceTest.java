package com.xianyusmart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import com.xianyusmart.context.AccountScopeContext;
import com.xianyusmart.exception.BusinessException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class NotificationInboxServiceTest {

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="D2_NTF_NATIVE",matches="true")
    void nativeMysqlFiltersAggregateWithoutDuplicatesAndRollback() throws Exception {
        long first=Long.parseLong(System.getenv("D2_NTF_ACCOUNT_A"));
        long second=Long.parseLong(System.getenv("D2_NTF_ACCOUNT_B"));
        long channel=Long.parseLong(System.getenv("D2_NTF_CHANNEL"));
        String prefix="QA-NTF-"+first;
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:13308/xianyusmart?serverTimezone=UTC&sslMode=DISABLED",System.getenv("DB_USERNAME"),System.getenv("DB_PASSWORD"));
        try(var connection=source.getConnection()) {
            var db=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true));
            var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource());
            var tx=manager.getTransaction(new org.springframework.transaction.support.DefaultTransactionDefinition());
            long connectionId=db.queryForObject("SELECT CONNECTION_ID()",Long.class);
            try {
                assertEquals(67,db.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1",Integer.class));
                assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('xianyu_account','xianyu_cookie','xianyu_notification_channel','xianyu_notification_event','xianyu_notification_outbox') AND engine='InnoDB'",Integer.class));
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM xianyu_account WHERE id IN (?,?)",Integer.class,first,second));
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM xianyu_notification_channel WHERE id=?",Integer.class,channel));
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM xianyu_notification_event WHERE event_id LIKE ?",Integer.class,prefix+"%"));
                for(long id:new long[]{first,second})db.update("INSERT INTO xianyu_account (id,tenant_id,account_note,unb,status) VALUES (?,1,?,?,0)",id,prefix,"QA-NTF-SELLER-"+id);
                db.update("INSERT INTO xianyu_notification_channel (id,tenant_id,channel_name,channel_type,webhook_url,event_types,config_json,enabled) VALUES (?,1,?,'WEBHOOK','','DELIVERY_EXCEPTION','{}',0)",channel,prefix);
                String[] states={"NOT_CONFIGURED","PENDING","SENT","FAILED","PARTIAL","RETRYING_OR_FAILED"};
                String[][] deliveries={{},{"PENDING","PENDING"},{"SENT","SENT"},{"FAILED","FAILED"},{"SENT","PENDING"},{"FAILED","PENDING"}};
                for(int i=0;i<8;i++) {
                    String event=prefix+"-"+i;
                    db.update("INSERT INTO xianyu_notification_event (tenant_id,event_id,event_type,xianyu_account_id,severity,title,source,dedupe_key,handling_status,data_json,read_time) VALUES (1,?,?,?,?,?,'LOCAL_SYNTHETIC',?,?, '{}',?)",event,i==6?"HISTORICAL_UNKNOWN":"DELIVERY_EXCEPTION",i==7?second:first,i==6?"INFO":"ERROR",prefix,event,i==6?"RESOLVED":"IN_PROGRESS",i==6?new java.sql.Timestamp(1):null);
                    if(i<6)for(int j=0;j<deliveries[i].length;j++)db.update("INSERT INTO xianyu_notification_outbox (tenant_id,channel_id,event_type,xianyu_account_id,dedupe_key,event_id,title,content,data_json,status) VALUES (1,?,'DELIVERY_EXCEPTION',?,?,?,?, 'LOCAL_SYNTHETIC','{}',?)",channel,first,event+"-"+j,event,prefix,deliveries[i][j]);
                }
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM xianyu_cookie WHERE xianyu_account_id IN (?,?)",Integer.class,first,second));
                var access=mock(AccountAccessService.class);
                var actual=new NotificationInboxService(db,access,new ObjectMapper());
                TenantContext.set(1L);AccountScopeContext.set(false,java.util.Set.of(first,second));
                var all=actual.list("ALL",null,prefix,1,20);
                assertEquals(8L,all.get("total"));assertEquals(8,((List<?>)all.get("records")).size());
                for(int i=0;i<6;i++) {
                    var result=actual.list("ALL",first,prefix,1,20,"ERROR","DELIVERY_EXCEPTION","IN_PROGRESS",states[i]);
                    assertEquals(1L,result.get("total"));var rows=(List<Map<String,Object>>)result.get("records");assertEquals(1,rows.size());
                    assertEquals(prefix+"-"+i,rows.get(0).get("eventId"));assertEquals(states[i],rows.get(0).get("deliveryStatus"));
                }
                assertEquals(7L,actual.list("ALL",null,prefix,1,20,"ERROR",null,null,null).get("total"));
                assertEquals(7L,actual.list("ALL",null,prefix,1,20,null,"DELIVERY_EXCEPTION",null,null).get("total"));
                assertEquals(1L,actual.list("ALL",null,prefix,1,20,null,null,"RESOLVED",null).get("total"));
                assertEquals(3L,actual.list("ALL",null,prefix,1,20,null,null,null,"NOT_CONFIGURED").get("total"));
                var ids=new java.util.HashSet<String>();
                for(int page=1;page<=4;page++) {
                    var result=actual.list("ALL",null,prefix,page,2);assertEquals(8L,result.get("total"));assertEquals(4,result.get("totalPages"));
                    for(var row:(List<Map<String,Object>>)result.get("records"))assertTrue(ids.add((String)row.get("eventId")));
                }
                assertEquals(8,ids.size());assertTrue(((List<?>)actual.list("ALL",null,prefix,5,2).get("records")).isEmpty());
                assertEquals(7L,actual.list("UNREAD",null,prefix,1,20).get("total"));
                assertEquals(7L,actual.list("PENDING",null,prefix,1,20).get("total"));
                assertEquals(0L,actual.list("SENT",null,prefix,1,20).get("total")); // Old view uses actual delivery logs, not synthetic outbox state.
                AccountScopeContext.set(false,java.util.Set.of(first));
                assertEquals(7L,actual.list("ALL",null,prefix,1,20).get("total"));
                assertEquals(0L,actual.list("ALL",second,prefix,1,20).get("total"));
                AccountScopeContext.set(false,java.util.Set.of());assertEquals(0L,actual.list("ALL",null,prefix,1,20).get("total"));
                AccountScopeContext.set(false,java.util.Set.of(first,second));TenantContext.set(2L);
                assertEquals(0L,actual.list("ALL",null,prefix,1,20).get("total"));TenantContext.set(1L);
                for(int i=0;i<4;i++) {
                    String[] filters={null,null,null,null};filters[i]="INVALID' OR 1=1";
                    assertEquals(400,assertThrows(BusinessException.class,()->actual.list("ALL",null,prefix,1,20,filters[0],filters[1],filters[2],filters[3])).getCode());
                }
                assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(connectionId,db.queryForObject("SELECT CONNECTION_ID()",Long.class));
            } finally {
                manager.rollback(tx);TenantContext.clear();AccountScopeContext.clear();
                var fresh=new JdbcTemplate(source);
                for(var check:List.of(
                        new Object[]{"SELECT COUNT(*) FROM xianyu_account WHERE id IN (?,?)",new Object[]{first,second}},
                        new Object[]{"SELECT COUNT(*) FROM xianyu_cookie WHERE xianyu_account_id IN (?,?)",new Object[]{first,second}},
                        new Object[]{"SELECT COUNT(*) FROM xianyu_notification_channel WHERE id=?",new Object[]{channel}},
                        new Object[]{"SELECT COUNT(*) FROM xianyu_notification_event WHERE event_id LIKE ?",new Object[]{prefix+"%"}},
                        new Object[]{"SELECT COUNT(*) FROM xianyu_notification_outbox WHERE channel_id=? OR event_id LIKE ?",new Object[]{channel,prefix+"%"}})) {
                    assertEquals(0,db.queryForObject((String)check[0],Integer.class,(Object[])check[1]));
                    assertEquals(0,fresh.queryForObject((String)check[0],Integer.class,(Object[])check[1]));
                }
                System.out.println("NATIVE_ROLLBACK freshAccountCookieChannelEventOutbox=0 committed=false");
            }
        }
    }

    private JdbcTemplate jdbcTemplate;
    private NotificationInboxService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new NotificationInboxService(jdbcTemplate, mock(AccountAccessService.class), new ObjectMapper());
        TenantContext.set(8L);
    }

    @AfterEach
    void tearDown() { TenantContext.clear(); AccountScopeContext.clear(); }

    @Test void fourFiltersAreBoundAndCountAndPageUseTheSameScope() {
        AccountScopeContext.set(false,java.util.Set.of(3L));
        when(jdbcTemplate.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(1L);
        when(jdbcTemplate.queryForList(anyString(),any(Object[].class))).thenReturn(new ArrayList<>());
        service.list("ALL",3L,"needle",2,5," error ","delivery_exception","in_progress","partial");
        var countSql=ArgumentCaptor.forClass(String.class);var countArgs=ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(countSql.capture(),eq(Long.class),countArgs.capture());
        var pageSql=ArgumentCaptor.forClass(String.class);var pageArgs=ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(pageSql.capture(),pageArgs.capture());
        assertArrayEquals(new Object[]{8L,3L,"ERROR","DELIVERY_EXCEPTION","IN_PROGRESS","PARTIAL","%needle%","%needle%","%needle%"},countArgs.getValue());
        assertArrayEquals(countArgs.getValue(),java.util.Arrays.copyOf(pageArgs.getValue(),countArgs.getValue().length));
        assertEquals(5,pageArgs.getValue()[countArgs.getValue().length]);assertEquals(5,pageArgs.getValue()[countArgs.getValue().length+1]);
        String where=countSql.getValue().substring(countSql.getValue().indexOf(" WHERE event.tenant_id"));
        assertTrue(pageSql.getValue().contains(where+" ORDER BY"));assertTrue(where.contains("event.xianyu_account_id IN (3)"));
        assertFalse(countSql.getValue().contains("JOIN xianyu_notification_outbox"));assertFalse(countSql.getValue().contains("needle"));
    }

    @Test void illegalEnumsAreBusiness400BeforeDatabaseAccess() {
        for(int index=0;index<4;index++) {
            String[] filters={null,null,null,null};filters[index]="INVALID' OR 1=1";
            assertEquals(400,assertThrows(BusinessException.class,()->service.list("ALL",null,null,1,20,filters[0],filters[1],filters[2],filters[3])).getCode());
        }
        verifyNoInteractions(jdbcTemplate);
    }

    @Test void blanksAndLegacyUnknownEventsRemainReadableWithoutFilters() {
        var legacy=new java.util.LinkedHashMap<String,Object>(Map.of("eventType","HISTORICAL_UNKNOWN","deliveryTotal",0,"deliverySent",0,"deliveryFailed",0));
        when(jdbcTemplate.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(1L);
        when(jdbcTemplate.queryForList(anyString(),any(Object[].class))).thenReturn(new ArrayList<>(List.of(legacy)));
        service.list("ALL",null,null,1,20," ","",null,"");
        assertEquals("HISTORICAL_UNKNOWN",legacy.get("eventType"));assertEquals("NOT_CONFIGURED",legacy.get("deliveryStatus"));
    }

    @Test void allSixDeliveryAggregatesMatchDisplayAndAreAccepted() {
        String[] states={"NOT_CONFIGURED","PENDING","SENT","FAILED","PARTIAL","RETRYING_OR_FAILED"};
        int[][] counts={{0,0,0},{2,0,0},{2,2,0},{2,0,2},{2,1,1},{2,0,1}};
        when(jdbcTemplate.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(1L);
        for(int index=0;index<states.length;index++) {
            var row=new java.util.LinkedHashMap<String,Object>(Map.of("deliveryTotal",counts[index][0],"deliverySent",counts[index][1],"deliveryFailed",counts[index][2]));
            when(jdbcTemplate.queryForList(anyString(),any(Object[].class))).thenReturn(new ArrayList<>(List.of(row)));
            service.list("ALL",null,null,1,20,null,null,null,states[index]);assertEquals(states[index],row.get("deliveryStatus"));
        }
    }

    @Test void emptyAccountScopeRetainsDenyConditionAndLegacyViewsStillWork() {
        AccountScopeContext.set(false,java.util.Set.of());
        when(jdbcTemplate.queryForList(anyString(),any(Object[].class))).thenReturn(new ArrayList<>());
        for(String view:List.of("ALL","UNREAD","PENDING","SENT"))service.list(view,null,null,1,20);
        verify(jdbcTemplate,times(4)).queryForObject(contains("AND 1=0"),eq(Long.class),any(Object[].class));
    }

    @Test
    void internalRenewalReferencesAreNotPersistedInPublicInboxData() {
        service.record("CREDENTIAL_EXPIRED", 3L, "凭证失效", "请扫码",
                Map.of("_renewalImage", "secret-image-ref", "accountName", "店铺甲"));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        String json = String.valueOf(args.getValue()[11]);
        assertFalse(json.contains("secret-image-ref"));
        assertTrue(json.contains("店铺甲"));
    }
}
