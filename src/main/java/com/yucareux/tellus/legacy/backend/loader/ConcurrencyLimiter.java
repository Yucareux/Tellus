package com.yucareux.tellus.legacy.backend.loader;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class ConcurrencyLimiter {
    private final int maxConcurrency;

    private final AtomicInteger activeCount = new AtomicInteger();
    private final Queue<Supplier<CompletableFuture<?>>> tasks = new ConcurrentLinkedQueue<>();

    public ConcurrencyLimiter(final int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public <K, V> Loader<K, V> wrap(final Loader<K, V> loader) {
        return key -> submit(() -> loader.load(key));
    }

    public <R> CompletableFuture<R> submit(final Supplier<CompletableFuture<R>> task) {
        final CompletableFuture<R> future = new CompletableFuture<>();
        tasks.add(() -> {
            if (future.isCancelled()) {
                return CompletableFuture.completedFuture(null);
            }
            return task.get().whenComplete((result, throwable) -> {
                if (throwable != null) {
                    future.completeExceptionally(throwable);
                } else {
                    future.complete(result);
                }
            });
        });
        trySchedule();
        return future;
    }

    private void trySchedule() {
        while (true) {
            final int count = activeCount.get();
            if (count >= maxConcurrency) {
                return;
            }
            if (activeCount.compareAndSet(count, count + 1)) {
                final Supplier<CompletableFuture<?>> task = tasks.poll();
                if (task != null) {
                    task.get().whenComplete((r, t) -> {
                        activeCount.decrementAndGet();
                        trySchedule();
                    });
                } else {
                    activeCount.decrementAndGet();
                }
                return;
            }
        }
    }
}
