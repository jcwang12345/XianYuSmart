package com.xianyusmart.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class XianyuWebSocketClientHistoryTest {
    static class LocalClient extends XianyuWebSocketClient {
        final ObjectMapper json = new ObjectMapper();
        final Deque<Map<String,Object>> pages=new ArrayDeque<>();
        int calls;
        LocalClient() { super(URI.create("ws://127.0.0.1/never-connect"), Map.of(), "QA", 1L, null, null); }
        @Override public boolean isConnected() { return true; }
        @Override public void send(String text) {
            calls++;
            assertTrue(calls <= 26, "empty platform pages must have a finite local bound");
            try {
                String mid=json.readTree(text).path("headers").path("mid").asText();
                completePendingResponse(mid,200,Map.of("body",pages.isEmpty()?Map.of("userMessageModels",List.of(),"hasMore",true,"nextCursor",9007199254740991L-calls):pages.removeFirst()));
            } catch(java.io.IOException bad) { throw new AssertionError(bad); }
        }
    }
    @Test void emptyAdvancingPagesCannotLoopUntilPlatformTimeout() {
        LocalClient client=new LocalClient();
        assertDoesNotThrow(()->client.listConversationHistory("QA-session",500));
        assertTrue(client.calls <= 25);
    }
    @Test void onlyExplicitValidTerminalIsCompleteIncludingVerifiedEmpty() {
        for(Object terminal:List.of(false,"false",0,"0")) {
            var client=new LocalClient();client.pages.add(Map.of("userMessageModels",List.of(),"hasMore",terminal));
            var result=client.listConversationHistoryResult("QA-session",500);
            assertTrue(result.complete());assertEquals("TERMINAL",result.stopReason());assertTrue(result.messages().isEmpty());assertEquals(1,client.calls);
        }
    }
    @Test void missingTerminalOrListShapeNeverInventsFull() {
        for(var page:List.of(Map.<String,Object>of("userMessageModels",List.of()),Map.<String,Object>of("hasMore",false),Map.<String,Object>of("userMessageModels","wrong","hasMore",false))) {
            var client=new LocalClient();client.pages.add(page);assertFalse(client.listConversationHistoryResult("QA-session",500).complete());
        }
    }
    @Test void partialRowsSurviveMalformedMemberOrMissingCursor() {
        var row=Map.<String,Object>of("messageId","QA-safe");
        for(var page:List.of(Map.<String,Object>of("userMessageModels",List.of(row,"wrong"),"hasMore",false),Map.<String,Object>of("userMessageModels",List.of(row),"hasMore",true))) {
            var client=new LocalClient();client.pages.add(page);var result=client.listConversationHistoryResult("QA-session",500);assertFalse(result.complete());assertEquals(List.of(row),result.messages());
        }
    }
    @Test void cursorCyclesTerminateWithPartialWithoutAdditionalRequest() {
        var client=new LocalClient();client.pages.add(Map.of("userMessageModels",List.of(),"hasMore",true,"nextCursor",100));client.pages.add(Map.of("userMessageModels",List.of(),"hasMore",true,"nextCursor",50));client.pages.add(Map.of("userMessageModels",List.of(),"hasMore",true,"nextCursor",100));
        var result=client.listConversationHistoryResult("QA-session",500);assertEquals("INVALID_CURSOR",result.stopReason());assertFalse(result.complete());assertEquals(3,client.calls);
    }
    @Test void fiveHundredCapIsPartialEvenWhenLastEnvelopeSaysTerminal() {
        var client=new LocalClient();client.pages.add(Map.of("userMessageModels",java.util.Collections.nCopies(501,Map.of("messageId","QA-safe")),"hasMore",false));
        var result=client.listConversationHistoryResult("QA-session",500);assertFalse(result.complete());assertEquals(500,result.messages().size());assertEquals("MESSAGE_LIMIT",result.stopReason());assertEquals(1,client.calls);
    }
}
