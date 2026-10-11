package com.spring.problemdetails.service;

import com.spring.problemdetails.dto.CreateEmployeeRequest;
import com.spring.problemdetails.dto.UpdateEmployeeRequest;
import com.spring.problemdetails.exception.DuplicateResourceException;
import com.spring.problemdetails.exception.InvalidSalaryException;
import com.spring.problemdetails.exception.ResourceNotFoundException;
import com.spring.problemdetails.model.Employee;
import com.spring.problemdetails.repository.EmployeeRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EmployeeService {

    private final EmployeeRepository repository;

    public EmployeeService(EmployeeRepository repository) {
        this.repository = repository;
    }

    @Cacheable("employees-all")
    public List<Employee> findAll() {
        return repository.findAll();
    }

    @Cacheable(value = "employees", key = "#id")
    public Employee findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee with id %d not found".formatted(id)));
    }

    @CacheEvict(value = "employees-all", allEntries = true)
    public Employee create(CreateEmployeeRequest request) {
        // Optimistic insert: let the UNIQUE index enforce email uniqueness (one DB round-trip
        // instead of SELECT + INSERT) — better throughput under concurrent creates
        try {
            return repository.save(
                    request.name(), request.department(), request.email(), request.salary());
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateResourceException(
                    "Employee with email '%s' already exists".formatted(request.email()));
        }
    }

    @Caching(evict = {
            @CacheEvict(value = "employees", key = "#id"),
            @CacheEvict(value = "employees-all", allEntries = true)
    })
    public Employee update(Long id, UpdateEmployeeRequest request) {
        Employee existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee with id %d not found".formatted(id)));
        if (request.salary() < existing.salary() * 0.9) {
            throw new InvalidSalaryException(
                    "Salary reduction of more than 10%% is not permitted",
                    existing.salary(),
                    request.salary()
            );
        }
        return repository.update(
                id, request.name(), request.department(), request.email(), request.salary());
    }

    @Caching(evict = {
            @CacheEvict(value = "employees", key = "#id"),
            @CacheEvict(value = "employees-all", allEntries = true)
    })
    public void delete(Long id) {
        repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee with id %d not found".formatted(id)));
        repository.deleteById(id);
    }
}
