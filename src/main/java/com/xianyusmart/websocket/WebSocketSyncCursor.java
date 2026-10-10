package com.xianyusmart.websocket;

import java.util.Map;

/**
 * WebSocket 消息同步游标
 */
public record WebSocketSyncCursor(long pts, long seq, long timestamp) {

    /** An old server cursor can stall the live subscription instead of replaying weeks of data. */
    static final long MAX_REALTIME_REPLAY_AGE_MS = 24L * 60 * 60 * 1000;
    static final long MAX_FUTURE_SKEW_MS = 5L * 60 * 1000;

    public static WebSocketSyncCursor initial() {
        long timestamp = System.currentTimeMillis();
        return new WebSocketSyncCursor(timestamp * 1000, 0, timestamp);
    }

    public static WebSocketSyncCursor forRealtime(WebSocketSyncCursor stored, long nowMillis) {
        if (stored == null || stored.pts() <= 0) {
            return new WebSocketSyncCursor(nowMillis * 1000, 0, nowMillis);
        }
        long ptsMillis = stored.pts() / 1000;
        long cursorMillis = stored.timestamp() > 0 ? stored.timestamp() : ptsMillis;
        if (cursorMillis <= 0 || nowMillis - cursorMillis > MAX_REALTIME_REPLAY_AGE_MS
                || cursorMillis - nowMillis > MAX_FUTURE_SKEW_MS
                || Math.abs(cursorMillis - ptsMillis) > MAX_FUTURE_SKEW_MS) {
            return new WebSocketSyncCursor(nowMillis * 1000, 0, nowMillis);
        }
        return stored;
    }

    public static WebSocketSyncCursor fromSyncPackage(Map<String, Object> syncPackage,
                                                      WebSocketSyncCursor previous) {
        if (syncPackage == null) {
            return previous;
        }
        long pts = firstPositive(syncPackage.get("pts"), syncPackage.get("endPts"),
                syncPackage.get("highPts"));
        if (pts <= 0 || (previous != null && pts <= previous.pts())) {
            return previous;
        }
        long seq = longValue(syncPackage.get("seq"));
        long timestamp = longValue(syncPackage.get("timestamp"));
        return new WebSocketSyncCursor(pts, seq, timestamp > 0 ? timestamp : pts / 1000);
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static long firstPositive(Object... values) {
        for (Object value : values) {
            long parsed = longValue(value);
            if (parsed > 0) {
                return parsed;
            }
        }
        return 0;
    }
}
