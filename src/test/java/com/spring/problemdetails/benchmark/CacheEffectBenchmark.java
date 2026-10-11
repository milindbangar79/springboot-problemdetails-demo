package com.spring.problemdetails.benchmark;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.spring.problemdetails.model.Employee;
import com.spring.problemdetails.repository.EmployeeRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates the article's Technique 3 (Multi-Layer Caching) impact.
 *
 * Expected results (from article's production data):
 *   withL1Cache — sub-microsecond latency, ~10x higher throughput than DB
 *   noCache     — SQLite latency (~0.1–0.5ms for in-memory), full DB round-trip
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 5)
@Fork(0)
public class CacheEffectBenchmark {

    private HikariDataSource dataSource;
    private EmployeeRepository repository;
    private Cache<Long, Employee> l1Cache;

    @Setup(Level.Trial)
    public void setup() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:file:bench_cache?mode=memory&cache=shared");
        config.setMaximumPoolSize(1);
        dataSource = new HikariDataSource(config);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS employees (
                    id INTEGER PRIMARY KEY, name TEXT NOT NULL,
                    department TEXT NOT NULL, email TEXT NOT NULL, salary REAL NOT NULL
                )""");
        jdbc.execute("DELETE FROM employees");
        jdbc.execute(
                "INSERT INTO employees VALUES (1,'Alice Johnson','Engineering','alice@example.com',95000)");

        repository = new EmployeeRepository(jdbc);

        l1Cache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(30))
                .recordStats()
                .build();
        // Pre-warm cache — simulates steady-state after initial population
        l1Cache.put(1L, repository.findById(1L).orElseThrow());
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        dataSource.close();
    }

    /** L1 Caffeine cache hit — no DB call (article: 72% of reads, <1 µs latency) */
    @Benchmark
    public Employee findById_withL1Cache(Blackhole bh) {
        Employee e = l1Cache.getIfPresent(1L);
        if (e == null) {
            e = repository.findById(1L).orElseThrow();
            l1Cache.put(1L, e);
        }
        return e;
    }

    /** Direct DB hit — baseline without any caching */
    @Benchmark
    public Employee findById_noCache(Blackhole bh) {
        return repository.findById(1L).orElseThrow();
    }
}
