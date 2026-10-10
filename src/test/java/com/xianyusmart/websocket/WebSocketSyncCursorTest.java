package com.xianyusmart.websocket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketSyncCursorTest {

    private static final long NOW = 1_801_299_200_000L;

    @Test
    void reusesRecentStoredCursorForShortDisconnect() {
        WebSocketSyncCursor recent = new WebSocketSyncCursor((NOW - 60_000) * 1000, 7, NOW - 60_000);
        assertSame(recent, WebSocketSyncCursor.forRealtime(recent, NOW));
    }

    @Test
    void resetsWeeksOldCursorToRestoreLiveSubscription() {
        long old = NOW - 26L * 24 * 60 * 60 * 1000;
        WebSocketSyncCursor stale = new WebSocketSyncCursor(old * 1000, 0, old);

        WebSocketSyncCursor actual = WebSocketSyncCursor.forRealtime(stale, NOW);

        assertEquals(NOW * 1000, actual.pts());
        assertEquals(NOW, actual.timestamp());
        assertEquals(0, actual.seq());
    }

    @Test
    void resetsFutureOrInconsistentCursorInsteadOfStallingSubscription() {
        WebSocketSyncCursor future = new WebSocketSyncCursor((NOW + 600_000) * 1000, 1, NOW + 600_000);
        WebSocketSyncCursor inconsistent = new WebSocketSyncCursor((NOW - 26L * 24 * 60 * 60 * 1000) * 1000,
                1, NOW);

        assertEquals(NOW * 1000, WebSocketSyncCursor.forRealtime(future, NOW).pts());
        assertEquals(NOW * 1000, WebSocketSyncCursor.forRealtime(inconsistent, NOW).pts());
    }

    @Test
    void initializesMissingCursor() {
        assertEquals(NOW * 1000, WebSocketSyncCursor.forRealtime(null, NOW).pts());
    }
}
