package com.spring.problemdetails.benchmark;

import com.spring.problemdetails.model.Employee;
import com.spring.problemdetails.repository.EmployeeRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures raw CRUD throughput against an embedded SQLite database with WAL mode.
 *
 * Run via BenchmarkRunner.main() or:
 *   mvn test-compile exec:java -Dexec.mainClass=com.spring.problemdetails.benchmark.BenchmarkRunner
 *      -Dexec.classpathScope=test
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 5)
@Fork(0)
public class EmployeeCrudBenchmark {

    private HikariDataSource dataSource;
    private EmployeeRepository repository;
    private final AtomicLong counter = new AtomicLong(100);

    @Setup(Level.Trial)
    public void setup() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:file:bench_crud?mode=memory&cache=shared");
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(5_000);
        dataSource = new HikariDataSource(config);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS employees (
                    id         INTEGER PRIMARY KEY,
                    name       TEXT    NOT NULL,
                    department TEXT    NOT NULL,
                    email      TEXT    NOT NULL,
                    salary     REAL    NOT NULL
                )""");
        jdbc.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_emp_email ON employees(LOWER(email))");
        jdbc.execute("DELETE FROM employees");
        jdbc.execute(
                "INSERT INTO employees VALUES (1,'Alice Johnson','Engineering','alice@example.com',95000)");
        jdbc.execute(
                "INSERT INTO employees VALUES (2,'Bob Smith','Marketing','bob@example.com',72000)");
        jdbc.execute(
                "INSERT INTO employees VALUES (3,'Carol White','HR','carol@example.com',68000)");

        repository = new EmployeeRepository(jdbc);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        dataSource.close();
    }

    @Benchmark
    public List<Employee> findAll() {
        return repository.findAll();
    }

    @Benchmark
    public Employee findById(Blackhole bh) {
        return repository.findById(1L).orElseThrow();
    }

    @Benchmark
    public Employee create() {
        long seq = counter.getAndIncrement();
        return repository.save("Bench User " + seq, "Engineering", "user" + seq + "@bench.com", 50_000);
    }

    @Benchmark
    public Employee update() {
        // Update is idempotent — same employee, incremented salary value from counter
        long salary = 95_000 + (counter.get() % 1_000);
        return repository.update(1L, "Alice Johnson", "Engineering", "alice@example.com", salary);
    }
}
