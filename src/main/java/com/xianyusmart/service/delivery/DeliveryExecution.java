package com.xianyusmart.service.delivery;

import java.util.function.BooleanSupplier;

/** Per-claim execution identity. Never propagate this scope to unrelated async work. */
public final class DeliveryExecution implements AutoCloseable {
    private static final ThreadLocal<DeliveryExecution> CURRENT = new ThreadLocal<>();
    private final String token;
    private final BooleanSupplier active;
    private final BooleanSupplier begin;
    private boolean started;

    public DeliveryExecution(String token, BooleanSupplier active, BooleanSupplier begin) {
        if (CURRENT.get() != null) throw new IllegalStateException("发货执行上下文不能嵌套");
        this.token = token;
        this.active = active;
        this.begin = begin;
        CURRENT.set(this);
    }

    public static String token() { return CURRENT.get() == null ? null : CURRENT.get().token; }
    public static void check() {
        DeliveryExecution scope = CURRENT.get();
        if (scope != null && !scope.active.getAsBoolean()) throw new LeaseLostException();
    }
    public static void beforeExternal() {
        check();
        DeliveryExecution scope = CURRENT.get();
        if (scope != null && !scope.started) {
            if (!scope.begin.getAsBoolean()) throw new LeaseLostException();
            scope.started = true;
        }
    }
    public static boolean started() { return CURRENT.get() != null && CURRENT.get().started; }
    @Override public void close() { CURRENT.remove(); }
    public static final class LeaseLostException extends IllegalStateException {
        public LeaseLostException() { super("发货执行租约已失效，停止旧任务"); }
    }
}
