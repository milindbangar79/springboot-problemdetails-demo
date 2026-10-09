package com.spring.problemdetails.advice;

import com.spring.problemdetails.exception.DuplicateResourceException;
import com.spring.problemdetails.exception.InvalidSalaryException;
import com.spring.problemdetails.exception.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;

/**
 * Translates domain exceptions into RFC 9457 ProblemDetail responses.
 *
 * Spring automatically handles MethodArgumentNotValidException (validation failures)
 * as ProblemDetail when spring.mvc.problemdetails.enabled=true, so only custom
 * exceptions need explicit handlers here.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problemDetail.setTitle("Resource Not Found");
        problemDetail.setType(URI.create("https://example.com/errors/resource-not-found"));
        problemDetail.setProperty("timestamp", Instant.now());
        problemDetail.setProperty("error_code", "ERR_RES_404");
        return problemDetail;
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ProblemDetail handleDuplicateResource(DuplicateResourceException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problemDetail.setTitle("Duplicate Resource");
        problemDetail.setType(URI.create("https://example.com/errors/duplicate-resource"));
        problemDetail.setProperty("timestamp", Instant.now());
        problemDetail.setProperty("error_code", "ERR_DUPLICATE_409");
        return problemDetail;
    }

    @ExceptionHandler(InvalidSalaryException.class)
    public ProblemDetail handleInvalidSalary(InvalidSalaryException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problemDetail.setTitle("Business Rule Violation");
        problemDetail.setType(URI.create("https://example.com/errors/business-rule-violation"));
        problemDetail.setProperty("timestamp", Instant.now());
        problemDetail.setProperty("error_code", "ERR_SALARY_422");
        problemDetail.setProperty("current_salary", ex.getCurrentSalary());
        problemDetail.setProperty("proposed_salary", ex.getProposedSalary());
        return problemDetail;
    }
}
