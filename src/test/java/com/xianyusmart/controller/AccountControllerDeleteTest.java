package com.xianyusmart.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.AccountLifecycleService;
import com.xianyusmart.service.WebSocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountControllerDeleteTest {
    final AccountController controller=new AccountController();
    final AccountLifecycleService lifecycle=mock(AccountLifecycleService.class);
    final WebSocketService websocket=mock(WebSocketService.class);
    final JsonNode command=new ObjectMapper().createObjectNode().put("accountId",900001L).put("requestId","QA-R02-delete");
    @BeforeEach void setup(){ReflectionTestUtils.setField(controller,"accountLifecycleService",lifecycle);ReflectionTestUtils.setField(controller,"webSocketService",websocket);}
    @Test void failedCommandCannotStopConnection(){
        when(lifecycle.delete(command)).thenThrow(new BusinessException(400,"Missing confirmation"));
        assertThrows(BusinessException.class,()->controller.deleteAccount(command));verifyNoInteractions(websocket);
    }
    @Test void committedDeleteStopsOnlyItsConnection(){
        var result=new LinkedHashMap<String,Object>(Map.of("accountId",900001L,"replayed",false));
        when(lifecycle.delete(command)).thenReturn(result);when(websocket.stopWebSocket(900001L)).thenReturn(true);
        assertEquals(200,controller.deleteAccount(command).getCode());verify(websocket).stopWebSocket(900001L);verify(lifecycle).recordConnectionOutcome(result,"STOPPED");
    }
    @Test void replayHasNoConnectionSideEffect(){
        when(lifecycle.delete(command)).thenReturn(new LinkedHashMap<>(Map.of("accountId",900001L,"replayed",true)));
        assertEquals(200,controller.deleteAccount(command).getCode());verifyNoInteractions(websocket);
    }
    @Test void failedStopDoesNotUndoCommittedDelete(){
        var result=new LinkedHashMap<String,Object>(Map.of("accountId",900001L,"replayed",false));
        when(lifecycle.delete(command)).thenReturn(result);when(websocket.stopWebSocket(900001L)).thenThrow(new IllegalStateException("QA only"));
        assertEquals(200,controller.deleteAccount(command).getCode());verify(lifecycle).recordConnectionOutcome(result,"STOP_FAILED");
    }
}
