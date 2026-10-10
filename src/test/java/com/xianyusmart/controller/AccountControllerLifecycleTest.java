package com.xianyusmart.controller;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xianyusmart.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;
class AccountControllerLifecycleTest {
 @Test void onlyNewActiveTransitionExplicitlyResumesConnection(){
  var controller=new AccountController();var lifecycle=mock(AccountLifecycleService.class);var socket=mock(WebSocketService.class);
  ReflectionTestUtils.setField(controller,"accountLifecycleService",lifecycle);ReflectionTestUtils.setField(controller,"webSocketService",socket);
  var input=new ObjectMapper().createObjectNode().put("accountId",900001L);
  var result=new LinkedHashMap<String,Object>(Map.of("replayed",false,"changedFields",List.of("lifecycleState"),"profile",Map.of("accountId",900001L,"lifecycleState","ACTIVE","platformStatus",1)));
  when(lifecycle.command(input,true)).thenReturn(result);when(socket.startWebSocket(900001L)).thenReturn(true);when(socket.isConnected(900001L)).thenReturn(true);
  controller.changeLifecycle(input);verify(socket).startWebSocket(900001L);verify(lifecycle).recordConnectionOutcome(result,"CONNECTED");
  result.put("replayed",true);controller.changeLifecycle(input);verify(socket,times(1)).startWebSocket(900001L);
  result.put("replayed",false);result.put("changedFields",List.of());controller.changeLifecycle(input);verify(socket,times(1)).startWebSocket(900001L);
 }
 @Test void restoredDisabledOrOfficialVerificationDoesNotConnect(){
  var controller=new AccountController();var lifecycle=mock(AccountLifecycleService.class);var socket=mock(WebSocketService.class);
  ReflectionTestUtils.setField(controller,"accountLifecycleService",lifecycle);ReflectionTestUtils.setField(controller,"webSocketService",socket);
  var input=new ObjectMapper().createObjectNode().put("accountId",900001L);
  var result=new LinkedHashMap<String,Object>(Map.of("replayed",false,"changedFields",List.of("lifecycleState"),"profile",Map.of("accountId",900001L,"lifecycleState","DISABLED","platformStatus",1)));
  when(lifecycle.command(input,true)).thenReturn(result);controller.changeLifecycle(input);verify(socket).stopWebSocket(900001L);verify(socket,never()).startWebSocket(anyLong());
  result.put("profile",Map.of("accountId",900001L,"lifecycleState","ACTIVE","platformStatus",-2));controller.changeLifecycle(input);verify(socket,never()).startWebSocket(anyLong());verify(lifecycle).recordConnectionOutcome(result,"VERIFICATION_REQUIRED");
 }
}
