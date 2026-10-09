package com.spring.problemdetails.service;

import com.spring.problemdetails.dto.CreateEmployeeRequest;
import com.spring.problemdetails.dto.UpdateEmployeeRequest;
import com.spring.problemdetails.exception.DuplicateResourceException;
import com.spring.problemdetails.exception.InvalidSalaryException;
import com.spring.problemdetails.exception.ResourceNotFoundException;
import com.spring.problemdetails.model.Employee;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class EmployeeService {

    private final Map<Long, Employee> store = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(3);

    public EmployeeService() {
        store.put(1L, new Employee(1L, "Alice Johnson", "Engineering", "alice@example.com", 95000));
        store.put(2L, new Employee(2L, "Bob Smith", "Marketing", "bob@example.com", 72000));
        store.put(3L, new Employee(3L, "Carol White", "HR", "carol@example.com", 68000));
    }

    public List<Employee> findAll() {
        return new ArrayList<>(store.values());
    }

    public Employee findById(Long id) {
        Employee employee = store.get(id);
        if (employee == null) {
            throw new ResourceNotFoundException("Employee with id %d not found".formatted(id));
        }
        return employee;
    }

    public Employee create(CreateEmployeeRequest request) {
        boolean emailTaken = store.values().stream()
                .anyMatch(e -> e.email().equalsIgnoreCase(request.email()));
        if (emailTaken) {
            throw new DuplicateResourceException(
                    "Employee with email '%s' already exists".formatted(request.email()));
        }
        long id = idSequence.incrementAndGet();
        Employee employee = new Employee(id, request.name(), request.department(), request.email(), request.salary());
        store.put(id, employee);
        return employee;
    }

    public Employee update(Long id, UpdateEmployeeRequest request) {
        Employee existing = findById(id);
        // Business rule: salary cannot be reduced by more than 10%
        if (request.salary() < existing.salary() * 0.9) {
            throw new InvalidSalaryException(
                    "Salary reduction of more than 10%% is not permitted",
                    existing.salary(),
                    request.salary()
            );
        }
        Employee updated = new Employee(id, request.name(), request.department(), request.email(), request.salary());
        store.put(id, updated);
        return updated;
    }

    public void delete(Long id) {
        findById(id);
        store.remove(id);
    }
}
