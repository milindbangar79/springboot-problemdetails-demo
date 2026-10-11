# springboot-problemdetails-demo

A focused reference project demonstrating two things together:

1. **RFC 9457 ProblemDetail** — replacing custom error-response objects with Spring's built-in `ProblemDetail` API.
2. **High-throughput design** — techniques from production systems to handle 50,000+ requests/second on a single instance, with JMH benchmarks to measure each technique.

- **Spring Boot**: 4.1.1
- **Java**: 25
- **Database**: SQLite (embedded, WAL mode) via `spring-boot-starter-jdbc`
- **Domain**: Employee CRUD — persisted in `employees.db`, seeded with 3 rows on first start

---

## Background — RFC 9457

Before RFC 9457 (and its predecessor RFC 7807), every team invented their own error body shape. A consumer had to read documentation to know whether an error came back as `{ "errorCode": ... }`, `{ "code": ... }`, `{ "errors": [...] }`, or something else entirely. Comparing errors across services was painful.

RFC 9457 defines a single, machine-readable format for HTTP error responses using the content type `application/problem+json`.

### Standard Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `type` | URI string | No | Identifies the problem type. Consumers can dereference it for documentation. Defaults to `about:blank` if omitted. |
| `title` | string | No | Short, human-readable summary of the problem type. Should not change between occurrences. |
| `status` | integer | No | HTTP status code. Mirrors the response status line. |
| `detail` | string | No | Human-readable explanation specific to *this* occurrence of the problem. |
| `instance` | URI string | No | URI identifying the specific occurrence (e.g. the request path). |

Extension fields (any additional key-value pairs) are allowed and travel alongside the standard fields in the same JSON object. This is where you add things like `timestamp`, `error_code`, `trace_id`, etc.

### Example response shape

```json
{
  "type": "https://example.com/errors/resource-not-found",
  "title": "Resource Not Found",
  "status": 404,
  "detail": "Employee with id 999 not found",
  "timestamp": "2026-10-09T15:43:07.520Z",
  "error_code": "ERR_RES_404"
}
```

The content type is always `application/problem+json`.

---

## Spring Boot Support

Spring Boot ships `org.springframework.http.ProblemDetail` since version 3.0. Two things wire it up:

### 1. Enable the flag

```yaml
# application.yml
spring:
  mvc:
    problemdetails:
      enabled: true
```

With this flag set, Spring's built-in `ResponseEntityExceptionHandler` converts its own exceptions — `MethodArgumentNotValidException`, `HttpMessageNotReadableException`, `NoHandlerFoundException`, etc. — into `ProblemDetail` automatically. Without it those exceptions produce a plain `DefaultErrorAttributes` response.

### 2. Use `ProblemDetail` in your own handlers

For custom domain exceptions, you write `@ExceptionHandler` methods that build and return a `ProblemDetail`:

```java
@ExceptionHandler(ResourceNotFoundException.class)
public ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {

    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    pd.setTitle("Resource Not Found");
    pd.setType(URI.create("https://example.com/errors/resource-not-found"));

    // extension properties — serialized as top-level JSON fields
    pd.setProperty("timestamp", Instant.now());
    pd.setProperty("error_code", "ERR_RES_404");

    return pd;
}
```

### Key API methods on `ProblemDetail`

| Method | Purpose |
|--------|---------|
| `ProblemDetail.forStatus(HttpStatus)` | Creates a `ProblemDetail` with only the status set |
| `ProblemDetail.forStatusAndDetail(HttpStatus, String)` | Creates with status + detail message |
| `pd.setTitle(String)` | Sets the `title` field |
| `pd.setType(URI)` | Sets the `type` field |
| `pd.setInstance(URI)` | Sets the `instance` field |
| `pd.setProperty(String, Object)` | Adds an extension field (any key/value) |

---

## Why This Matters Over Custom Error Objects

Consider the alternative — a hand-rolled error DTO:

```java
// Before: every team does this differently
public class ApiError {
    private int status;
    private String message;
    private String errorCode;
    private LocalDateTime timestamp;
    // getters, constructors, Jackson annotations...
}
```

Problems with custom DTOs:
- Shape varies across teams and services — no common contract.
- Clients cannot distinguish `application/problem+json` from a success response by content type alone.
- Adding a new field means updating the DTO, its serialization config, and all consumer parsing code.
- No machine-readable `type` URI pointing consumers to documentation.

With `ProblemDetail`:
- The shape is defined by an RFC — any client that understands RFC 9457 can handle your errors without custom parsing.
- Extension fields are added via `setProperty()` with no DTO changes.
- The `type` URI is a stable, documentable identifier for each class of error.
- Spring handles the `Content-Type: application/problem+json` header automatically.

---

## High-Throughput Design

This project applies four techniques from the article [*How I Handle 1 Million Requests/Second with Java*](https://medium.com/@hianupamsinha/how-i-handle-1-million-requests-second-with-java-1e17d68f5fca) to reach a 50,000 RPS target on a single instance. Each technique is independently measurable via the JMH benchmarks in `src/test/java/.../benchmark/`.

---

### Technique 1 — Virtual Threads (Project Loom)

**File:** `src/main/resources/application.yml`

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

This single property switches Tomcat from its default fixed-size platform thread pool to a virtual-thread-per-request executor. Virtual threads are cheap to create (no OS thread per request), so the JVM can handle tens of thousands of concurrent requests without thread exhaustion. They park instead of blocking during I/O waits, freeing the underlying carrier threads to serve other requests.

**When it helps:** I/O-bound workloads — database calls, HTTP downstream calls, file reads. It does not help CPU-bound work.

**Gotcha:** `synchronized` blocks pin a virtual thread to its carrier. The project uses `ReentrantLock` via Spring's `JdbcTemplate` and HikariCP internals, which are Loom-safe. Use `-Djdk.tracePinnedThreads=short` during load testing to detect any remaining pinning points.

**JMH result (1,000 tasks × 10 ms simulated I/O):**

| Model | Time to complete 1,000 tasks |
|---|---|
| Platform threads (200 fixed) | 70.7 ms |
| Virtual threads (unlimited) | 12.7 ms |

**5.5× faster.** Platform threads queue the 800 excess tasks; virtual threads start all 1,000 immediately.

---

### Technique 2 — SQLite with WAL Mode + HikariCP Tuning

**Files:** `src/main/java/.../config/DataSourceConfig.java`, `src/main/resources/schema.sql`, `src/main/resources/data.sql`

The original `ConcurrentHashMap` store was replaced with an embedded SQLite database. SQLite is deployed as part of the JVM process (no network hop), making it significantly faster than a remote database for single-instance workloads.

**Key SQLite tuning applied per connection via `SQLiteConfig`:**

| PRAGMA | Value | Effect |
|--------|-------|--------|
| `journal_mode` | `WAL` | Write-Ahead Logging: readers never block writers, writers never block readers. Multiple threads can read concurrently. |
| `synchronous` | `NORMAL` | Safe durability with WAL (only fsync at checkpoints), faster than `FULL`. |
| `cache_size` | `-64000` | 64 MB page cache per connection; hot rows stay in RAM. |
| `temp_store` | `MEMORY` | Temporary tables and indexes live in RAM, not on disk. |
| `foreign_keys` | `ON` | Enforces referential integrity. |

**Connection pool formula (from article):** `Pool Size = (CPU cores × 2) + effective spindle count`. For an SSD machine with 4 cores: `(4 × 2) + 1 = 9`. The default is set to 20 in production and 1 in the test profile (required for shared in-memory SQLite).

**`DataSourceConfig` provides a custom `JdbcTemplate` bean** that adds a SQLite-aware exception translator. Spring does not have built-in error-code mappings for SQLite (its SQL state is `null`), so without this, SQLite unique-constraint violations arrive as `UncategorizedSQLException` instead of `DataIntegrityViolationException`. The translator maps SQLite error code 19 (`SQLITE_CONSTRAINT`) to the correct Spring type, keeping service-layer catch blocks standard.

**Optimistic insert for email uniqueness:** Rather than issuing a `SELECT COUNT(*)` before each `INSERT` (two round trips), the repository attempts the insert directly and lets the `UNIQUE INDEX` on `LOWER(email)` enforce uniqueness. The resulting `DataIntegrityViolationException` is caught in `EmployeeService.create()` and re-thrown as `DuplicateResourceException`. This halves the write cost for the create path.

**JMH result (single-threaded, in-memory SQLite):**

| Operation | Throughput |
|---|---|
| `update` | 387,835 ops/s |
| `findById` | 269,541 ops/s |
| `findAll` | 232,824 ops/s |
| `create` | 209,155 ops/s |

---

### Technique 3 — Caffeine L1 In-Process Cache

**Files:** `src/main/java/.../config/CacheConfig.java`, `src/main/java/.../service/EmployeeService.java`

A Caffeine cache sits in front of every read path. Cache entries live for 30 seconds and the cache holds up to 10,000 entries. Spring's `@Cacheable`/`@CacheEvict` annotations manage the cache transparently — the controller and repository are unaware of it.

**Cache regions:**

| Cache name | Cached by | Evicted by |
|---|---|---|
| `employees` | `findById(id)` | `update(id, ...)`, `delete(id)` |
| `employees-all` | `findAll()` | `create(...)`, `update(id, ...)`, `delete(id)` |

**`CacheConfig` wires up the `CacheManager`:**

```java
@Bean
public CacheManager cacheManager() {
    CaffeineCacheManager manager = new CaffeineCacheManager("employees", "employees-all");
    manager.setCaffeine(Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .recordStats());
    return manager;
}
```

**JMH result (warm cache, single employee):**

| Path | Throughput |
|---|---|
| `findById` — no cache (direct SQLite) | 280,368 ops/s |
| `findById` — L1 cache hit (Caffeine) | 83,991,919 ops/s |

**299× higher throughput** on the read path once the cache is warm. The article reports L1 serving 72% of all reads at sub-microsecond latency in production.

---

### Technique 4 — Load Shedding

**File:** `src/main/java/.../filter/LoadSheddingFilter.java`

A `jakarta.servlet.Filter` counts active concurrent requests with an `AtomicInteger`. When the count exceeds the configured threshold (default: 5,000), the filter immediately returns HTTP `503 Service Unavailable` with a `Retry-After: 1` header and decrements the counter — the request never enters the servlet stack.

```java
int current = activeRequests.incrementAndGet();
if (current > maxConcurrentRequests) {
    activeRequests.decrementAndGet();
    response.setStatus(SC_SERVICE_UNAVAILABLE);
    response.setHeader("Retry-After", "1");
    return;
}
chain.doFilter(request, response);
```

**Why it matters:** The article describes a production launch where traffic spiked to 1.4M RPS — 40% above plan. Without load shedding, thread starvation would have cascaded across the cluster. With it, 6% of low-priority requests received a clean 503 with a retry hint, while the remaining 94% were served normally.

**Configuring the threshold:**

```yaml
# application.yml
app:
  max-concurrent-requests: 5000
```

---

### Response Compression

**File:** `src/main/resources/application.yml`

```yaml
server:
  compression:
    enabled: true
    mime-types: application/json,application/problem+json
    min-response-size: 1024
  http2:
    enabled: true
```

Tomcat compresses JSON responses larger than 1 KB with gzip. Smaller payloads travel faster, reducing time-in-flight and freeing network bandwidth for more concurrent requests.

---

## Project Structure

```
src/main/java/com/spring/problemdetails/
├── ProblemDetailsApplication.java          # @SpringBootApplication entry point
│
├── config/
│   ├── DataSourceConfig.java               # SQLite DataSource (WAL, cache, PRAGMAs) + JdbcTemplate
│   └── CacheConfig.java                    # @EnableCaching + Caffeine cache manager
│
├── filter/
│   └── LoadSheddingFilter.java             # jakarta.servlet.Filter — 503 above concurrent threshold
│
├── controller/
│   └── EmployeeController.java             # REST endpoints: /api/employees
│
├── service/
│   └── EmployeeService.java                # Business logic + @Cacheable/@CacheEvict annotations
│
├── repository/
│   └── EmployeeRepository.java             # JdbcTemplate CRUD against SQLite
│
├── model/
│   └── Employee.java                       # record: id, name, department, email, salary
│
├── dto/
│   ├── CreateEmployeeRequest.java          # Jakarta Validation constraints; salary >= 30000
│   └── UpdateEmployeeRequest.java          # Jakarta Validation constraints; no minimum salary floor
│
├── exception/
│   ├── ResourceNotFoundException.java      # Thrown when an employee ID is not found
│   ├── DuplicateResourceException.java     # Thrown when the email already exists
│   └── InvalidSalaryException.java         # Thrown when salary reduction exceeds 10%
│
└── advice/
    └── GlobalExceptionHandler.java         # @RestControllerAdvice — maps exceptions to ProblemDetail

src/main/resources/
├── application.yml                         # App config: virtual threads, compression, load shedding
├── schema.sql                              # CREATE TABLE IF NOT EXISTS employees (...)
└── data.sql                                # INSERT OR IGNORE — seeds 3 employees on first start

src/test/java/com/spring/problemdetails/
├── controller/
│   └── EmployeeControllerTest.java         # 12 integration tests: happy paths + all 4 error types
└── benchmark/
    ├── EmployeeCrudBenchmark.java          # SQLite CRUD throughput: findAll, findById, create, update
    ├── CacheEffectBenchmark.java           # Cache hit vs DB hit: shows the L1 multiplier
    ├── ThreadModelBenchmark.java           # Platform threads vs virtual threads under I/O load
    └── BenchmarkRunner.java                # main() entry point — runs all three benchmarks

src/test/resources/
└── application-test.yml                    # Overrides: in-memory SQLite (jdbc:sqlite::memory:?cache=shared), pool=1
```

---

## API Endpoints

Base URL: `http://localhost:8080`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/employees` | List all employees |
| `GET` | `/api/employees/{id}` | Get employee by ID |
| `POST` | `/api/employees` | Create a new employee |
| `PUT` | `/api/employees/{id}` | Update an employee |
| `DELETE` | `/api/employees/{id}` | Delete an employee |

Swagger UI is available at `http://localhost:8080/swagger-ui.html` and the raw OpenAPI spec at `http://localhost:8080/api-docs`.

### Seeded employees

The following three rows are inserted via `data.sql` at startup using `INSERT OR IGNORE`. They are present on every fresh start and survive restarts because the database is file-based (`employees.db` in the working directory). Any employees added via the API also persist between restarts.

| ID | Name | Department | Email | Salary |
|----|------|------------|-------|--------|
| 1 | Alice Johnson | Engineering | alice@example.com | 95000 |
| 2 | Bob Smith | Marketing | bob@example.com | 72000 |
| 3 | Carol White | HR | carol@example.com | 68000 |

To reset the database to the three seeded rows: stop the application and delete `employees.db`.

---

## Error Response Scenarios

### 404 — Resource Not Found

**Trigger:** `GET /api/employees/999` (ID does not exist), or `PUT`/`DELETE` on a non-existent ID.

**Exception thrown:** `ResourceNotFoundException`

```json
{
  "type": "https://example.com/errors/resource-not-found",
  "title": "Resource Not Found",
  "status": 404,
  "detail": "Employee with id 999 not found",
  "timestamp": "2026-10-09T15:43:07.520Z",
  "error_code": "ERR_RES_404"
}
```

---

### 409 — Duplicate Resource

**Trigger:** `POST /api/employees` with an email that already belongs to an existing employee.

**Exception thrown:** `DuplicateResourceException`

Request:
```json
{
  "name": "Alice Duplicate",
  "department": "Engineering",
  "email": "alice@example.com",
  "salary": 80000
}
```

Response:
```json
{
  "type": "https://example.com/errors/duplicate-resource",
  "title": "Duplicate Resource",
  "status": 409,
  "detail": "Employee with email 'alice@example.com' already exists",
  "timestamp": "2026-10-09T15:43:07.521Z",
  "error_code": "ERR_DUPLICATE_409"
}
```

---

### 422 — Business Rule Violation

**Trigger:** `PUT /api/employees/{id}` where the proposed salary is more than 10% below the current salary.

**Exception thrown:** `InvalidSalaryException` (carries `currentSalary` and `proposedSalary` as context)

Request (Alice's current salary is 95000; 90% of that is 85500):
```json
{
  "name": "Alice Johnson",
  "department": "Engineering",
  "email": "alice@example.com",
  "salary": 50000
}
```

Response — note the domain-specific extension fields `current_salary` and `proposed_salary`:
```json
{
  "type": "https://example.com/errors/business-rule-violation",
  "title": "Business Rule Violation",
  "status": 422,
  "detail": "Salary reduction of more than 10% is not permitted",
  "timestamp": "2026-10-09T15:43:07.522Z",
  "error_code": "ERR_SALARY_422",
  "current_salary": 95000.0,
  "proposed_salary": 50000.0
}
```

---

### 400 — Validation Failure (handled automatically by Spring)

**Trigger:** `POST` or `PUT` with a body that fails Jakarta Validation constraints (`@NotBlank`, `@Email`, `@Positive`, `@Min`).

**No custom handler needed.** Because `GlobalExceptionHandler` extends `ResponseEntityExceptionHandler` and `spring.mvc.problemdetails.enabled=true`, Spring intercepts `MethodArgumentNotValidException` and converts it to a `ProblemDetail` automatically.

Request:
```json
{
  "name": "",
  "department": "",
  "email": "not-an-email",
  "salary": -100
}
```

Response (`Content-Type: application/problem+json`):
```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Invalid request content.",
  "instance": "/api/employees"
}
```

---

## How the Pieces Connect

```
HTTP Request
     │
     ▼
LoadSheddingFilter          (503 immediately if > 5000 concurrent requests)
     │
     ▼
EmployeeController          (@Valid triggers bean validation)
     │
     ├── validation fails ──► MethodArgumentNotValidException
     │                              │
     │                              ▼
     │                    ResponseEntityExceptionHandler   ← spring.mvc.problemdetails.enabled=true
     │                         (built into Spring)
     │                              │
     │                              ▼
     │                      ProblemDetail (400)
     │
     ├── calls EmployeeService   (@Cacheable / @CacheEvict on every method)
     │         │
     │         ├── cache hit ──────────────────────────────► return cached value (no DB call)
     │         │
     │         ├── cache miss ──► EmployeeRepository ──► SQLite (WAL, Caffeine page cache)
     │         │                         │
     │         │                         └── result stored in Caffeine L1 cache
     │         │
     │         ├── ID not found ──► ResourceNotFoundException
     │         ├── duplicate email ► DuplicateResourceException
     │         └── salary cut > 10% ► InvalidSalaryException
     │                    │
     │                    ▼
     │           GlobalExceptionHandler
     │           (@RestControllerAdvice)
     │                    │
     │                    ▼
     │           ProblemDetail (404 / 409 / 422)
     │
     ▼
Response: Content-Type: application/json  |  application/problem+json
```

---

## Usage Patterns

### Pattern 1 — Custom exception carries extra context

When a business rule is violated, the exception itself can carry the context values that belong in the error response. The handler reads them via getters and passes them to `setProperty()`:

```java
// InvalidSalaryException holds both values
throw new InvalidSalaryException("...", existing.salary(), request.salary());

// Handler surfaces them as extension fields
pd.setProperty("current_salary", ex.getCurrentSalary());
pd.setProperty("proposed_salary", ex.getProposedSalary());
```

This keeps the service layer unaware of HTTP concerns while still producing a rich error response.

### Pattern 2 — Let Spring handle framework exceptions

You do not need to write handlers for `MethodArgumentNotValidException`, `HttpMessageNotReadableException`, `NoHandlerFoundException`, etc. Extending `ResponseEntityExceptionHandler` and enabling the flag is sufficient. Only write handlers for your own domain exceptions.

### Pattern 3 — Stable `type` URIs as error identifiers

The `type` field should be a stable URI per error category — one URI per class of problem, not per occurrence. Clients can use it as a reliable discriminator to branch their error-handling logic, independent of the `status` code or `title` string which may be localised.

---

## JMH Benchmarks

Three JMH benchmark classes measure the high-throughput techniques in isolation. They run entirely in-process (`@Fork(0)`) using an in-memory SQLite database, so no running application instance is needed.

### Prerequisites

Java 25 and Maven 3.9+ (same as the main application). No additional setup is required.

### Step 1 — Compile

The JMH annotation processor generates benchmark runner stubs during test compilation. Run this once, or whenever you change a benchmark class:

```bash
mvn test-compile
```

You will see generated Java files appear under:

```
target/generated-test-sources/test-annotations/
  com/spring/problemdetails/benchmark/jmh_generated/
```

These are compiled automatically into `target/test-classes/`.

### Step 2 — Run all benchmarks

```bash
mvn exec:java \
  -Dexec.mainClass=com.spring.problemdetails.benchmark.BenchmarkRunner \
  -Dexec.classpathScope=test
```

This runs all three benchmark suites sequentially. Total wall-clock time is approximately **3 minutes** (3 warmup iterations × 2 s + 5 measurement iterations × 5 s, per benchmark method, across all methods).

Results are printed to the console and also written to:

```
target/benchmark-results.json
```

### Step 3 — Run a single benchmark class

To run only one benchmark class, pass the simple class name as an argument to `BenchmarkRunner`, or use the `include` filter directly:

```bash
# Only the cache benchmark
mvn exec:java \
  -Dexec.mainClass=com.spring.problemdetails.benchmark.BenchmarkRunner \
  -Dexec.classpathScope=test \
  -Dexec.args="CacheEffectBenchmark"
```

Alternatively, edit `BenchmarkRunner.main()` to call `.include("CacheEffectBenchmark")` only, then re-run.

### Step 4 — Read the output

JMH prints a result table at the end of the run. Example output from this machine (Java 25, Apple M-series, in-memory SQLite):

```
Benchmark                                       Mode  Cnt         Score         Error  Units
CacheEffectBenchmark.findById_noCache          thrpt    5    280,368 ±    2,711  ops/s
CacheEffectBenchmark.findById_withL1Cache      thrpt    5  83,991,919 ± 4,529,415  ops/s
EmployeeCrudBenchmark.create                   thrpt    5    209,155 ±   35,384  ops/s
EmployeeCrudBenchmark.findAll                  thrpt    5    232,824 ±    2,657  ops/s
EmployeeCrudBenchmark.findById                 thrpt    5    269,541 ±    1,489  ops/s
EmployeeCrudBenchmark.update                   thrpt    5    387,835 ±    7,993  ops/s
ThreadModelBenchmark.platformThreads_200            ss   10     70.754 ±   2.700  ms/op
ThreadModelBenchmark.virtualThreads_unlimited       ss   10     12.765 ±   1.116  ms/op
```

**How to read the columns:**

| Column | Meaning |
|--------|---------|
| `Mode` | `thrpt` = throughput (ops/second, higher is better). `ss` = single-shot time (ms per invocation, lower is better). |
| `Cnt` | Number of measurement iterations whose scores were averaged. |
| `Score` | The primary result — throughput or latency. |
| `Error` | ± half-width of the 99.9% confidence interval across iterations. A large error relative to the score means high variance; the number should be treated cautiously. |
| `Units` | `ops/s` for throughput benchmarks, `ms/op` for single-shot benchmarks. |

### What each benchmark measures

#### `EmployeeCrudBenchmark` — raw SQLite throughput

Measures the throughput of each CRUD operation against an in-memory SQLite database with no cache. Use this to understand the baseline cost of each database operation.

- `findAll` — full table scan, returns all rows as `List<Employee>`
- `findById` — indexed primary-key lookup by `id`
- `create` — `INSERT` with generated key retrieval; each invocation inserts a new unique row
- `update` — `UPDATE` of a fixed row (idempotent; no net data change)

#### `CacheEffectBenchmark` — Caffeine L1 cache vs direct DB

Compares two code paths for `findById`:

- `findById_withL1Cache` — checks Caffeine cache first; returns the cached `Employee` record without touching SQLite (warm cache throughout the benchmark)
- `findById_noCache` — goes directly to SQLite on every call

The ~300× throughput difference is the multiplier you get by serving reads from the L1 cache instead of the database.

#### `ThreadModelBenchmark` — platform threads vs virtual threads

Submits 1,000 tasks to two executor types. Each task sleeps for 10 ms to simulate a blocking I/O call (a database round-trip or downstream HTTP request).

- `platformThreads_200` — `Executors.newFixedThreadPool(200)`. The first 200 tasks run immediately; the remaining 800 queue up. Wall time ≈ `ceil(1000 / 200) × 10 ms` ≈ 50 ms minimum, plus scheduling overhead.
- `virtualThreads_unlimited` — `Executors.newVirtualThreadPerTaskExecutor()`. All 1,000 tasks start immediately. Each virtual thread parks during `Thread.sleep()` and releases its carrier thread. Wall time ≈ 10 ms (single I/O hop for all tasks in parallel).

Mode is `SingleShotTime` (ms per invocation): you are measuring how long it takes to complete the whole batch of 1,000 tasks, not individual task throughput.

### Notes on benchmark methodology

**`@Fork(0)` — in-process execution**

JMH's default `@Fork(1)` spawns a child JVM for each benchmark. When running via Maven's `exec:java`, Maven loads classes through a custom classloader rather than the system classpath, so the forked child JVM cannot locate `org.openjdk.jmh.runner.ForkedMain`. Using `@Fork(0)` runs all benchmarks in the same JVM as the launcher. The `@Warmup` iterations still drive JIT compilation to steady state before measurement begins, so relative comparisons between methods are valid.

**Warmup and measurement settings:**

```java
@Warmup(iterations = 3, time = 2)       // 3 × 2s warmup per method
@Measurement(iterations = 5, time = 5)  // 5 × 5s measurement per method
```

For the `ThreadModelBenchmark` (single-shot mode):
```java
@Warmup(iterations = 3, batchSize = 1)
@Measurement(iterations = 10, batchSize = 1)
```

**Results vary by machine.** CPU core count, memory bandwidth, and JIT optimisations all affect the absolute numbers. The ratios between methods (e.g., cache hit vs miss) are more informative than the raw scores.

---

## Running the Application

**Prerequisites:** Java 25, Maven 3.9+

```bash
mvn spring-boot:run
```

The application starts on port `8080`. On the first start, `employees.db` is created in the working directory and the three seed rows are inserted. Subsequent starts reuse the existing file.

**Try an error response immediately:**

```bash
# 404
curl -s http://localhost:8080/api/employees/999 | jq .

# 409 — alice@example.com is seeded on startup
curl -s -X POST http://localhost:8080/api/employees \
  -H "Content-Type: application/json" \
  -d '{"name":"X","department":"Y","email":"alice@example.com","salary":50000}' | jq .

# 400 — validation failure
curl -s -X POST http://localhost:8080/api/employees \
  -H "Content-Type: application/json" \
  -d '{"name":"","department":"","email":"bad","salary":-1}' | jq .
```

**Run the tests:**

```bash
mvn test
```

Tests run against an in-memory SQLite database (`@ActiveProfiles("test")` activates `application-test.yml`) so they are fully isolated from the `employees.db` file and from each other across runs. All 12 tests cover the happy paths and all four error scenarios (400, 404, 409, 422), verifying both the HTTP status and the `Content-Type: application/problem+json` header.

**Run the JMH benchmarks:**

```bash
mvn test-compile && mvn exec:java \
  -Dexec.mainClass=com.spring.problemdetails.benchmark.BenchmarkRunner \
  -Dexec.classpathScope=test
```

See the [JMH Benchmarks](#jmh-benchmarks) section above for a full walkthrough of the output.
