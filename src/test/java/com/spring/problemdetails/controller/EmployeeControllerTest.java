package com.spring.problemdetails.controller;

import tools.jackson.databind.ObjectMapper;
import com.spring.problemdetails.dto.CreateEmployeeRequest;
import com.spring.problemdetails.dto.UpdateEmployeeRequest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmployeeControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    // ── Happy-path tests ──────────────────────────────────────────────────────

    @Test
    @Order(1)
    void getAllEmployees_returnsEmployeeList() throws Exception {
        mockMvc.perform(get("/api/employees"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    @Order(2)
    void getEmployeeById_existingId_returnsEmployee() throws Exception {
        mockMvc.perform(get("/api/employees/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Alice Johnson"))
                .andExpect(jsonPath("$.department").value("Engineering"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.salary").value(95000.0));
    }

    @Test
    @Order(3)
    void createEmployee_validRequest_returnsCreated() throws Exception {
        var request = new CreateEmployeeRequest("Dave Test", "Finance", "dave@example.com", 55000);

        mockMvc.perform(post("/api/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Dave Test"))
                .andExpect(jsonPath("$.email").value("dave@example.com"))
                .andExpect(jsonPath("$.salary").value(55000.0));
    }

    @Test
    @Order(4)
    void updateEmployee_validRequest_returnsUpdatedEmployee() throws Exception {
        var request = new UpdateEmployeeRequest("Alice Johnson", "Engineering", "alice@example.com", 100000);

        mockMvc.perform(put("/api/employees/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salary").value(100000.0));
    }

    @Test
    @Order(5)
    void deleteEmployee_existingId_returnsNoContent() throws Exception {
        // create an employee to delete so the seeded data stays intact
        var createReq = new CreateEmployeeRequest("Temp Delete", "Temp", "temp-delete@example.com", 40000);
        String body = mockMvc.perform(post("/api/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(delete("/api/employees/" + id))
                .andExpect(status().isNoContent());
    }

    // ── ProblemDetail 404 – Resource Not Found ────────────────────────────────

    @Test
    @Order(10)
    void getEmployeeById_nonExistentId_returnsProblemDetail404() throws Exception {
        mockMvc.perform(get("/api/employees/9999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.detail").value("Employee with id 9999 not found"))
                .andExpect(jsonPath("$.error_code").value("ERR_RES_404"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @Order(11)
    void updateEmployee_nonExistentId_returnsProblemDetail404() throws Exception {
        var request = new UpdateEmployeeRequest("Ghost", "Unknown", "ghost@example.com", 50000);

        mockMvc.perform(put("/api/employees/9999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error_code").value("ERR_RES_404"));
    }

    @Test
    @Order(12)
    void deleteEmployee_nonExistentId_returnsProblemDetail404() throws Exception {
        mockMvc.perform(delete("/api/employees/9999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error_code").value("ERR_RES_404"));
    }

    // ── ProblemDetail 409 – Duplicate Resource ────────────────────────────────

    @Test
    @Order(20)
    void createEmployee_duplicateEmail_returnsProblemDetail409() throws Exception {
        var request = new CreateEmployeeRequest("Alice Duplicate", "Engineering", "alice@example.com", 80000);

        mockMvc.perform(post("/api/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Duplicate Resource"))
                .andExpect(jsonPath("$.detail").value("Employee with email 'alice@example.com' already exists"))
                .andExpect(jsonPath("$.error_code").value("ERR_DUPLICATE_409"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ── ProblemDetail 422 – Business Rule Violation ───────────────────────────

    @Test
    @Order(30)
    void updateEmployee_salaryReductionOver10Percent_returnsProblemDetail422() throws Exception {
        // Alice current salary = 100000 (updated in order 4); 90% = 90000; 50000 < 90000 → triggers exception
        var request = new UpdateEmployeeRequest("Alice Johnson", "Engineering", "alice@example.com", 50000);

        mockMvc.perform(put("/api/employees/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.title").value("Business Rule Violation"))
                .andExpect(jsonPath("$.error_code").value("ERR_SALARY_422"))
                .andExpect(jsonPath("$.current_salary").value(100000.0))
                .andExpect(jsonPath("$.proposed_salary").value(50000.0))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ── ProblemDetail 400 – Bean Validation (handled automatically by Spring) ─

    @Test
    @Order(40)
    void createEmployee_blankFields_returnsProblemDetail400() throws Exception {
        String invalid = """
                {
                  "name": "",
                  "department": "",
                  "email": "not-valid",
                  "salary": -500
                }
                """;

        mockMvc.perform(post("/api/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @Order(41)
    void updateEmployee_invalidBody_returnsProblemDetail400() throws Exception {
        String invalid = """
                {
                  "name": "",
                  "department": "",
                  "email": "not-valid",
                  "salary": -1
                }
                """;

        mockMvc.perform(put("/api/employees/2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400));
    }
}
