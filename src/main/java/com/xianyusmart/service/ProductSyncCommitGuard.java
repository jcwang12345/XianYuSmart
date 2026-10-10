package com.xianyusmart.service;

import java.util.function.Supplier;

/** The owning durable worker must still hold its task row before any detail snapshot commit. */
public final class ProductSyncCommitGuard {
    private static final ThreadLocal<Runnable> CHECK = new ThreadLocal<>();
    private ProductSyncCommitGuard() {}
    public static <T> T withAuthority(Runnable check, Supplier<T> work) {
        Runnable previous = CHECK.get(); CHECK.set(check);
        try { return work.get(); } finally { if(previous==null) CHECK.remove(); else CHECK.set(previous); }
    }
    public static void requireAuthority() { Runnable check=CHECK.get(); if(check!=null) check.run(); }
}
