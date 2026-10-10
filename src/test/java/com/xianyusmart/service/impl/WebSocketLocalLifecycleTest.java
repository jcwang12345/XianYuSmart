package com.xianyusmart.service.impl;
import com.xianyusmart.service.AccountPolicyFixture;
import com.xianyusmart.service.R01QaPlatformService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class WebSocketLocalLifecycleTest {
 @Test void qaConnectionUsesTheSameManualStopAndExplicitResumeSemantics(){
  var socket=new WebSocketServiceImpl();AccountPolicyFixture.active(socket);var qa=mock(R01QaPlatformService.class);when(qa.handles(1L)).thenReturn(true);ReflectionTestUtils.setField(socket,"r01Qa",qa);
  assertTrue(socket.startWebSocket(1L));assertTrue(socket.stopWebSocket(1L));assertFalse(socket.ensureConnected(1L));verify(qa,times(1)).connection(1L,true);verify(qa).connection(1L,false);
  assertTrue(socket.startWebSocket(1L));verify(qa,times(2)).connection(1L,true);
 }
}
