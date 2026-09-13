package com.xianyusmart.context;

import java.util.Set;

/** 当前请求允许访问的闲鱼账号范围；后台任务无该上下文时不做账号级过滤。 */
public final class AccountScopeContext {

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private AccountScopeContext() {
    }

    public static void set(boolean unrestricted, Set<Long> accountIds) {
        CURRENT.set(new Scope(unrestricted, accountIds == null ? Set.of() : Set.copyOf(accountIds)));
    }

    public static Scope get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public record Scope(boolean unrestricted, Set<Long> accountIds) {
    }
}
