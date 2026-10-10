package com.xianyusmart.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.controller.dto.MsgContextReqDTO;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.mapper.*;
import com.xianyusmart.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ChatMessageServiceImplHistoryTest {
    /** Native QA only: actual mappers and projection share the caller's rollback transaction. */
    public static void verifyNativeHistoryPersistence(org.springframework.jdbc.core.JdbcTemplate db,long first,long second,String base,long now) throws Exception {
        assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        factory.setDataSource(db.getDataSource());
        var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(XianyuAccountMapper.class);
        configuration.addMapper(XianyuChatMessageMapper.class);
        factory.setConfiguration(configuration);
        factory.setPlugins(new com.xianyusmart.config.MybatisPlusConfig().mybatisPlusInterceptor());
        var template=new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        var socket=mock(WebSocketService.class);
        var projection=new ConversationAssignmentService(db);
        var service=new ChatMessageServiceImpl();
        ReflectionTestUtils.setField(service,"webSocketService",socket);
        ReflectionTestUtils.setField(service,"accountMapper",template.getMapper(XianyuAccountMapper.class));
        ReflectionTestUtils.setField(service,"chatMessageMapper",template.getMapper(XianyuChatMessageMapper.class));
        ReflectionTestUtils.setField(service,"objectMapper",new ObjectMapper());
        ReflectionTestUtils.setField(service,"conversationAssignmentService",projection);
        com.xianyusmart.context.TenantContext.set(1L);
        com.xianyusmart.context.AccountScopeContext.set(false,Set.of(first,second));
        String sid=base+"-history";
        for(long account:new long[]{first,second}) {
            long inquiry=now-(account==first?900000:600000),reply=inquiry+120000;
            String seller="FG-SELLER-"+account;
            var rows=List.<Map<String,Object>>of(
                    Map.of("message",Map.of("id","same-early","senderUserId",seller,"timestamp",inquiry-60000)),
                    Map.of("message",Map.of("id","same-inquiry","senderUserId","FG-QA-BUYER","timestamp",inquiry)),
                    Map.of("message",Map.of("id","same-reply","senderUserId",seller,"timestamp",reply)));
            when(socket.listConversationHistoryResult(account,sid,500)).thenReturn(new WebSocketService.HistoryResult(rows,true,"TERMINAL"));
            var request=new MsgContextReqDTO();request.setXianyuAccountId(account);request.setSid(sid);
            for(int attempt=0;attempt<2;attempt++) {
                assertEquals("FULL",((Map<?,?>)service.syncContextMessages(request).getData()).get("coverageStatus"));
                projection.refresh();
            }
            assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM xianyu_chat_message WHERE tenant_id=1 AND xianyu_account_id=? AND s_id=?",Integer.class,account,sid));
            var state=db.queryForMap("SELECT CAST(UNIX_TIMESTAMP(first_message_time)*1000 AS SIGNED) inquiry,CAST(UNIX_TIMESTAMP(first_response_time)*1000 AS SIGNED) reply,CAST(UNIX_TIMESTAMP(sla_due_time)*1000 AS SIGNED) due,history_coverage_status coverage,history_last_synced_time synced FROM conversation_assignment WHERE tenant_id=1 AND xianyu_account_id=? AND session_id=?",account,sid);
            assertEquals(inquiry,((Number)state.get("inquiry")).longValue());assertEquals(reply,((Number)state.get("reply")).longValue());assertEquals(inquiry+300000,((Number)state.get("due")).longValue());assertEquals("FULL",state.get("coverage"));
            when(socket.listConversationHistoryResult(account,sid,500)).thenThrow(new IllegalStateException("LOCAL_SYNTHETIC_FAILURE"));
            assertThrows(IllegalStateException.class,()->service.syncContextMessages(request));
            var failed=db.queryForMap("SELECT history_coverage_status coverage,history_sync_status status,history_last_synced_time synced,history_last_error error FROM conversation_assignment WHERE tenant_id=1 AND xianyu_account_id=? AND session_id=?",account,sid);
            assertEquals("PARTIAL",failed.get("coverage"));assertEquals("FAILED",failed.get("status"));assertEquals(state.get("synced"),failed.get("synced"));assertEquals("HISTORY_SYNC_FAILED",failed.get("error"));
            verify(socket,times(3)).listConversationHistoryResult(account,sid,500);
        }
        String unknown=base+"-unknown";
        var invalid=List.<Map<String,Object>>of(Map.of("message",Map.of("senderUserId","FG-QA-BUYER","extension",Map.of("summary","LOCAL_SYNTHETIC"))));
        when(socket.listConversationHistoryResult(first,unknown,500)).thenReturn(new WebSocketService.HistoryResult(invalid,true,"TERMINAL"));
        var request=new MsgContextReqDTO();request.setXianyuAccountId(first);request.setSid(unknown);
        for(int attempt=0;attempt<2;attempt++)assertEquals("PARTIAL",((Map<?,?>)service.syncContextMessages(request).getData()).get("coverageStatus"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM xianyu_chat_message WHERE xianyu_account_id=? AND s_id=? AND message_time IS NULL",Integer.class,first,unknown));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM conversation_assignment WHERE xianyu_account_id=? AND session_id=?",Integer.class,first,unknown));
        verify(socket,times(2)).listConversationHistoryResult(first,unknown,500);
        verifyNoMoreInteractions(socket);
    }
    private ChatMessageServiceImpl service(WebSocketService socket,XianyuChatMessageMapper messages,ConversationAssignmentService assignments) {
        var accounts=mock(XianyuAccountMapper.class);when(accounts.selectById(1790923507201L)).thenReturn(new XianyuAccount());var service=new ChatMessageServiceImpl();
        ReflectionTestUtils.setField(service,"webSocketService",socket);ReflectionTestUtils.setField(service,"accountMapper",accounts);ReflectionTestUtils.setField(service,"chatMessageMapper",messages);ReflectionTestUtils.setField(service,"objectMapper",new ObjectMapper());ReflectionTestUtils.setField(service,"conversationAssignmentService",assignments);return service;
    }
    private MsgContextReqDTO request(){var request=new MsgContextReqDTO();request.setXianyuAccountId(1790923507201L);request.setSid("QA-session");return request;}
    @Test void legacyListWithoutTerminalEvidenceNeverClaimsFull() {
        var socket=mock(WebSocketService.class,CALLS_REAL_METHODS);
        when(socket.listConversationHistory(1790923507201L,"QA-session",500)).thenReturn(List.of());
        var accounts=mock(XianyuAccountMapper.class);when(accounts.selectById(1790923507201L)).thenReturn(new XianyuAccount());
        var service=new ChatMessageServiceImpl();
        ReflectionTestUtils.setField(service,"webSocketService",socket);
        ReflectionTestUtils.setField(service,"accountMapper",accounts);
        ReflectionTestUtils.setField(service,"chatMessageMapper",mock(XianyuChatMessageMapper.class));
        ReflectionTestUtils.setField(service,"objectMapper",new ObjectMapper());
        ReflectionTestUtils.setField(service,"conversationAssignmentService",mock(ConversationAssignmentService.class));
        var request=new MsgContextReqDTO();request.setXianyuAccountId(1790923507201L);request.setSid("QA-session");
        var result=service.syncContextMessages(request);
        assertEquals("PARTIAL",((Map<?,?>)result.getData()).get("coverageStatus"));
    }
    @Test void trustedTerminalAndOriginalTimeAllowFullWithoutCountingImportTime() {
        long original=System.currentTimeMillis()-600000;var socket=mock(WebSocketService.class);var messages=mock(XianyuChatMessageMapper.class);var assignments=mock(ConversationAssignmentService.class);
        when(socket.listConversationHistoryResult(1790923507201L,"QA-session",500)).thenReturn(new WebSocketService.HistoryResult(List.of(Map.of("message",Map.of("id","QA-one","senderUserId","QA-buyer","timestamp",original))),true,"TERMINAL"));
        var response=service(socket,messages,assignments).syncContextMessages(request());assertEquals("FULL",((Map<?,?>)response.getData()).get("coverageStatus"));
        var saved=org.mockito.ArgumentCaptor.forClass(com.xianyusmart.entity.XianyuChatMessage.class);verify(messages).insert(saved.capture());assertEquals(original,saved.getValue().getMessageTime());verify(assignments).markHistorySync(1790923507201L,"QA-session",1,500,true,null);
    }
    @Test void missingIllegalAndFutureOriginalTimeNeverBecomeAccurateImportedTime() {
        for(var fields:List.of(Map.<String,Object>of(),Map.<String,Object>of("timestamp",-1),Map.<String,Object>of("timestamp",System.currentTimeMillis()+3600000))) {
            var message=new HashMap<String,Object>(fields);message.put("id","QA-unknown");message.put("senderUserId","QA-buyer");var socket=mock(WebSocketService.class);var saved=mock(XianyuChatMessageMapper.class);var assignments=mock(ConversationAssignmentService.class);
            when(socket.listConversationHistoryResult(1790923507201L,"QA-session",500)).thenReturn(new WebSocketService.HistoryResult(List.of(Map.of("message",message)),true,"TERMINAL"));
            var response=service(socket,saved,assignments).syncContextMessages(request());assertEquals("PARTIAL",((Map<?,?>)response.getData()).get("coverageStatus"));var capture=org.mockito.ArgumentCaptor.forClass(com.xianyusmart.entity.XianyuChatMessage.class);verify(saved).insert(capture.capture());assertNull(capture.getValue().getMessageTime());verify(assignments).markHistorySync(1790923507201L,"QA-session",1,500,false,null);
        }
    }
    @Test void platformFailurePersistsOnlyFixedSafeErrorAndNeverFull() {
        var socket=mock(WebSocketService.class);var messages=mock(XianyuChatMessageMapper.class);var assignments=mock(ConversationAssignmentService.class);when(socket.listConversationHistoryResult(1790923507201L,"QA-session",500)).thenThrow(new IllegalStateException("SYNTHETIC_PRIVATE_ERROR"));
        var error=assertThrows(IllegalStateException.class,()->service(socket,messages,assignments).syncContextMessages(request()));assertFalse(error.getMessage().contains("PRIVATE"));verifyNoInteractions(messages);verify(assignments).markHistorySync(1790923507201L,"QA-session",0,500,false,"HISTORY_SYNC_FAILED");
    }
    @Test void replayWithoutOriginalIdOrTimeKeepsStableIdentityAndUnknownTime()throws Exception {
        var socket=mock(WebSocketService.class);var saved=mock(XianyuChatMessageMapper.class);var assignments=mock(ConversationAssignmentService.class);var model=Map.<String,Object>of("message",Map.of("senderUserId","QA-buyer","extension",Map.of("summary","LOCAL_SYNTHETIC")));
        when(socket.listConversationHistoryResult(1790923507201L,"QA-session",500)).thenReturn(new WebSocketService.HistoryResult(List.of(model),true,"TERMINAL"));var service=service(socket,saved,assignments);
        service.syncContextMessages(request());Thread.sleep(5);service.syncContextMessages(request());var capture=org.mockito.ArgumentCaptor.forClass(com.xianyusmart.entity.XianyuChatMessage.class);verify(saved,times(2)).insert(capture.capture());assertEquals(capture.getAllValues().get(0).getPnmId(),capture.getAllValues().get(1).getPnmId());assertNull(capture.getAllValues().get(0).getMessageTime());assertNull(capture.getAllValues().get(1).getMessageTime());verify(assignments,times(2)).markHistorySync(1790923507201L,"QA-session",1,500,false,null);
    }
    @Test void originalIdWinsEvenWhenTimeMissingAndStableFallbackIgnoresMapOrder() {
        var socket=mock(WebSocketService.class);var saved=mock(XianyuChatMessageMapper.class);var assignments=mock(ConversationAssignmentService.class);
        var a=new LinkedHashMap<String,Object>();a.put("senderUserId","QA-buyer");a.put("extension",Map.of("summary","LOCAL_SYNTHETIC"));var b=new LinkedHashMap<String,Object>();b.put("extension",Map.of("summary","LOCAL_SYNTHETIC"));b.put("senderUserId","QA-buyer");
        when(socket.listConversationHistoryResult(1790923507201L,"QA-session",500)).thenReturn(new WebSocketService.HistoryResult(List.of(Map.of("message",a)),true,"TERMINAL"),new WebSocketService.HistoryResult(List.of(Map.of("message",b)),true,"TERMINAL"),new WebSocketService.HistoryResult(List.of(Map.of("message",Map.of("id","QA-original-id","senderUserId","QA-buyer"))),true,"TERMINAL"));
        var service=service(socket,saved,assignments);service.syncContextMessages(request());service.syncContextMessages(request());service.syncContextMessages(request());var capture=org.mockito.ArgumentCaptor.forClass(com.xianyusmart.entity.XianyuChatMessage.class);verify(saved,times(3)).insert(capture.capture());assertEquals(capture.getAllValues().get(0).getPnmId(),capture.getAllValues().get(1).getPnmId());assertEquals("QA-original-id",capture.getAllValues().get(2).getPnmId());
    }
}
