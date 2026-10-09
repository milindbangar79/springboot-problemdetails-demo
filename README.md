# springboot-problemdetails-demo

A focused reference project showing how to replace custom error-response objects with Spring's built-in `ProblemDetail` API, which implements the **RFC 9457** standard for HTTP error responses.

- **Spring Boot**: 4.1.1
- **Java**: 25
- **Domain**: Employee CRUD (in-memory, stubbed data)

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

## Project Structure

```
src/main/java/com/spring/problemdetails/
├── ProblemDetailsApplication.java          # @SpringBootApplication entry point
│
├── controller/
│   └── EmployeeController.java             # REST endpoints: /api/employees
│
├── service/
│   └── EmployeeService.java                # In-memory ConcurrentHashMap store, seeded with 3 employees
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

### Seeded employees (resets on restart)

| ID | Name | Department | Email | Salary |
|----|------|------------|-------|--------|
| 1 | Alice Johnson | Engineering | alice@example.com | 95000 |
| 2 | Bob Smith | Marketing | bob@example.com | 72000 |
| 3 | Carol White | HR | carol@example.com | 68000 |

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
     ├── calls EmployeeService
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
Response: Content-Type: application/problem+json
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

## Running the Application

**Prerequisites:** Java 25, Maven 3.9+

```bash
mvn spring-boot:run
```

The application starts on port `8080`.

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

All 12 tests cover the happy paths and all four error scenarios (400, 404, 409, 422), verifying both the HTTP status and the `Content-Type: application/problem+json` header.
