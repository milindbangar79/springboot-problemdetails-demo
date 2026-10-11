package com.spring.problemdetails.repository;

import com.spring.problemdetails.model.Employee;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class EmployeeRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Employee> ROW_MAPPER = (rs, rowNum) -> new Employee(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("department"),
            rs.getString("email"),
            rs.getDouble("salary")
    );

    public EmployeeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Employee> findAll() {
        return jdbc.query(
                "SELECT id, name, department, email, salary FROM employees ORDER BY id",
                ROW_MAPPER);
    }

    public Optional<Employee> findById(Long id) {
        return jdbc.query(
                "SELECT id, name, department, email, salary FROM employees WHERE id = ?",
                ROW_MAPPER, id).stream().findFirst();
    }

    public Employee save(String name, String department, String email, double salary) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO employees (name, department, email, salary) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, department);
            ps.setString(3, email);
            ps.setDouble(4, salary);
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return new Employee(id, name, department, email, salary);
    }

    public Employee update(Long id, String name, String department, String email, double salary) {
        jdbc.update(
                "UPDATE employees SET name = ?, department = ?, email = ?, salary = ? WHERE id = ?",
                name, department, email, salary, id);
        return new Employee(id, name, department, email, salary);
    }

    public void deleteById(Long id) {
        jdbc.update("DELETE FROM employees WHERE id = ?", id);
    }
}
