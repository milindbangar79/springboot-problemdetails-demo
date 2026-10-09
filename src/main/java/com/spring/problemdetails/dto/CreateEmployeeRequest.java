package com.spring.problemdetails.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CreateEmployeeRequest(
        @NotBlank(message = "Name is required") String name,
        @NotBlank(message = "Department is required") String department,
        @Email(message = "Must be a valid email address") @NotBlank(message = "Email is required") String email,
        @Positive(message = "Salary must be positive") @Min(value = 30000, message = "Salary must be at least 30000") double salary
) {}
