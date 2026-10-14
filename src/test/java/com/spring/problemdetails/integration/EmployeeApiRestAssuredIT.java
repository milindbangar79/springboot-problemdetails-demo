package com.spring.problemdetails.integration;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * End-to-end integration tests using RestAssured against a full Spring Boot
 * server on a random port with in-memory SQLite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmployeeApiRestAssuredIT {

    @LocalServerPort
    int port;

    int createdEmployeeId;

    @BeforeEach
    void setUp() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = port;
        RestAssured.basePath = "/api/employees";
    }

    // ── Happy-path CRUD ───────────────────────────────────────────────────────

    @Test
    @Order(1)
    void getAllEmployees_returnsNonEmptyList() {
        given()
            .when()
                .get()
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", not(empty()))
                .body("size()", greaterThanOrEqualTo(3));
    }

    @Test
    @Order(2)
    void getEmployeeById_existingId_returnsCorrectEmployee() {
        given()
            .when()
                .get("/1")
            .then()
                .statusCode(200)
                .body("id", equalTo(1))
                .body("name", equalTo("Alice Johnson"))
                .body("department", equalTo("Engineering"))
                .body("email", equalTo("alice@example.com"))
                .body("salary", greaterThan(90_000.0F));
    }

    @Test
    @Order(3)
    void createEmployee_validRequest_returns201WithEmployee() {
        String payload = """
                {
                  "name": "RestAssured Test User",
                  "department": "Finance",
                  "email": "ra-test@example.com",
                  "salary": 60000
                }
                """;

        createdEmployeeId = given()
                .contentType(ContentType.JSON)
                .body(payload)
            .when()
                .post()
            .then()
                .statusCode(201)
                .body("name", equalTo("RestAssured Test User"))
                .body("email", equalTo("ra-test@example.com"))
                .body("salary", equalTo(60_000.0F))
                .body("id", notNullValue())
                .extract().path("id");
    }

    @Test
    @Order(4)
    void updateEmployee_validRequest_returnsUpdatedEmployee() {
        String payload = """
                {
                  "name": "RestAssured Test User Updated",
                  "department": "Finance",
                  "email": "ra-test@example.com",
                  "salary": 65000
                }
                """;

        given()
            .contentType(ContentType.JSON)
            .body(payload)
            .when()
                .put("/{id}", createdEmployeeId)
            .then()
                .statusCode(200)
                .body("name", equalTo("RestAssured Test User Updated"))
                .body("salary", equalTo(65_000.0F));
    }

    @Test
    @Order(5)
    void deleteEmployee_existingId_returns204() {
        given()
            .when()
                .delete("/{id}", createdEmployeeId)
            .then()
                .statusCode(204);
    }

    @Test
    @Order(6)
    void getEmployee_afterDeletion_returns404() {
        given()
            .when()
                .get("/{id}", createdEmployeeId)
            .then()
                .statusCode(404);
    }

    // ── ProblemDetail 404 ─────────────────────────────────────────────────────

    @Test
    @Order(10)
    void getEmployee_nonExistentId_returns404ProblemDetail() {
        given()
            .when()
                .get("/99999")
            .then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("status", equalTo(404))
                .body("title", equalTo("Resource Not Found"))
                .body("detail", containsString("99999"))
                .body("error_code", equalTo("ERR_RES_404"))
                .body("timestamp", notNullValue())
                .body("type", containsString("resource-not-found"));
    }

    @Test
    @Order(11)
    void updateEmployee_nonExistentId_returns404ProblemDetail() {
        String payload = """
                {
                  "name": "Ghost",
                  "department": "Unknown",
                  "email": "ghost-ra@example.com",
                  "salary": 50000
                }
                """;

        given()
            .contentType(ContentType.JSON)
            .body(payload)
            .when()
                .put("/99999")
            .then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("status", equalTo(404))
                .body("error_code", equalTo("ERR_RES_404"));
    }

    @Test
    @Order(12)
    void deleteEmployee_nonExistentId_returns404ProblemDetail() {
        given()
            .when()
                .delete("/99999")
            .then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("status", equalTo(404))
                .body("error_code", equalTo("ERR_RES_404"));
    }

    // ── ProblemDetail 409 ─────────────────────────────────────────────────────

    @Test
    @Order(20)
    void createEmployee_duplicateEmail_returns409ProblemDetail() {
        String payload = """
                {
                  "name": "Alice Duplicate",
                  "department": "Engineering",
                  "email": "alice@example.com",
                  "salary": 80000
                }
                """;

        given()
            .contentType(ContentType.JSON)
            .body(payload)
            .when()
                .post()
            .then()
                .statusCode(409)
                .contentType("application/problem+json")
                .body("status", equalTo(409))
                .body("title", equalTo("Duplicate Resource"))
                .body("detail", containsString("alice@example.com"))
                .body("error_code", equalTo("ERR_DUPLICATE_409"))
                .body("timestamp", notNullValue())
                .body("type", containsString("duplicate-resource"));
    }

    // ── ProblemDetail 422 ─────────────────────────────────────────────────────

    @Test
    @Order(30)
    void updateEmployee_salaryReductionOver10Percent_returns422ProblemDetail() {
        // Bob (id=2) seeded at 72000; 90% = 64800; set 30000 < 64800 → triggers 422
        String payload = """
                {
                  "name": "Bob Smith",
                  "department": "Marketing",
                  "email": "bob@example.com",
                  "salary": 30000
                }
                """;

        given()
            .contentType(ContentType.JSON)
            .body(payload)
            .when()
                .put("/2")
            .then()
                .statusCode(422)
                .contentType("application/problem+json")
                .body("status", equalTo(422))
                .body("title", equalTo("Business Rule Violation"))
                .body("error_code", equalTo("ERR_SALARY_422"))
                .body("current_salary", notNullValue())
                .body("proposed_salary", equalTo(30_000.0F))
                .body("timestamp", notNullValue())
                .body("type", containsString("business-rule-violation"));
    }

    // ── ProblemDetail 400 Bean Validation ────────────────────────────────────

    @Test
    @Order(40)
    void createEmployee_blankFieldsAndNegativeSalary_returns400ProblemDetail() {
        String payload = """
                {
                  "name": "",
                  "department": "",
                  "email": "not-valid",
                  "salary": -500
                }
                """;

        given()
            .contentType(ContentType.JSON)
            .body(payload)
            .when()
                .post()
            .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("status", equalTo(400));
    }

    @Test
    @Order(41)
    void createEmployee_salaryBelowMinimum_returns400ProblemDetail() {
        String payload = """
                {
                  "name": "Under Minimum",
                  "department": "Ops",
                  "email": "under-min@example.com",
                  "salary": 100
                }
                """;

        given()
            .contentType(ContentType.JSON)
            .body(payload)
            .when()
                .post()
            .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("status", equalTo(400));
    }

    @Test
    @Order(42)
    void updateEmployee_invalidBody_returns400ProblemDetail() {
        String payload = """
                {
                  "name": "",
                  "department": "",
                  "email": "bad-email",
                  "salary": -1
                }
                """;

        given()
            .contentType(ContentType.JSON)
            .body(payload)
            .when()
                .put("/2")
            .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("status", equalTo(400));
    }
}
