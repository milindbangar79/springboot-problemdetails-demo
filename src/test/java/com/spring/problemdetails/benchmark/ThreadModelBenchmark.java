package com.spring.problemdetails.benchmark;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Reproduces the article's Technique 1 benchmark (Virtual Threads vs Platform Threads).
 *
 * Simulates 1,000 concurrent requests each blocking for 10 ms (e.g. a SQLite/network I/O wait).
 * With 200 platform threads the queue backs up; with virtual threads all 1,000 complete in ~10 ms.
 *
 * Article results (Java 21, 10 ms I/O simulation):
 *   Platform threads (200) — 18,500 ops/sec
 *   Virtual threads (unlimited) — 142,000 ops/sec  →  7.7× improvement
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, batchSize = 1)
@Measurement(iterations = 10, batchSize = 1)
@Fork(0)
public class ThreadModelBenchmark {

    private static final int SIMULATED_IO_MS = 10;
    private static final int CONCURRENT_TASKS = 1_000;

    /**
     * Classic thread-per-request model: 200 platform threads serving 1,000 requests.
     * The first 200 tasks run immediately; the remaining 800 wait in the queue.
     */
    @Benchmark
    public void platformThreads_200() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(200);
        CountDownLatch latch = new CountDownLatch(CONCURRENT_TASKS);
        for (int i = 0; i < CONCURRENT_TASKS; i++) {
            executor.submit(() -> {
                try {
                    Thread.sleep(SIMULATED_IO_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executor.shutdownNow();
    }

    /**
     * Virtual thread model: one virtual thread per task, unlimited concurrency.
     * All 1,000 tasks start immediately; carrier threads park during I/O and serve others.
     */
    @Benchmark
    public void virtualThreads_unlimited() throws InterruptedException {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(CONCURRENT_TASKS);
            for (int i = 0; i < CONCURRENT_TASKS; i++) {
                executor.submit(() -> {
                    try {
                        Thread.sleep(SIMULATED_IO_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        }
    }
}
