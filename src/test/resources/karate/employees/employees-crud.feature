Feature: Employee CRUD - Happy Path E2E

  Background:
    * def employeesUrl = baseUrl + '/employees'

  Scenario: GET all employees returns a non-empty list with correct schema
    Given url employeesUrl
    When method get
    Then status 200
    And match response == '#array'
    And assert response.length >= 3
    And match each response == { id: '#number', name: '#string', department: '#string', email: '#string', salary: '#number' }

  Scenario: GET employee by ID returns the seeded employee
    Given url employeesUrl + '/1'
    When method get
    Then status 200
    And match response.id == 1
    And match response.name == 'Alice Johnson'
    And match response.department == 'Engineering'
    And match response.email == 'alice@example.com'
    And match response.salary == '#number'

  Scenario: Full CRUD lifecycle - create, read, update, delete
    # --- CREATE ---
    Given url employeesUrl
    And request { name: 'Karate Lifecycle User', department: 'Testing', email: 'karate-lifecycle@example.com', salary: 50000 }
    When method post
    Then status 201
    And match response.id == '#number'
    And match response.name == 'Karate Lifecycle User'
    And match response.email == 'karate-lifecycle@example.com'
    And match response.salary == 50000.0
    * def createdId = response.id

    # --- READ ---
    Given url employeesUrl + '/' + createdId
    When method get
    Then status 200
    And match response.name == 'Karate Lifecycle User'
    And match response.email == 'karate-lifecycle@example.com'

    # --- UPDATE ---
    Given url employeesUrl + '/' + createdId
    And request { name: 'Karate Lifecycle User Updated', department: 'QA', email: 'karate-lifecycle@example.com', salary: 55000 }
    When method put
    Then status 200
    And match response.name == 'Karate Lifecycle User Updated'
    And match response.department == 'QA'
    And match response.salary == 55000.0

    # --- DELETE ---
    Given url employeesUrl + '/' + createdId
    When method delete
    Then status 204

    # --- VERIFY DELETED - should yield 404 ---
    Given url employeesUrl + '/' + createdId
    When method get
    Then status 404

  Scenario: POST employee with minimum valid salary is accepted
    Given url employeesUrl
    And request { name: 'Min Salary User', department: 'Operations', email: 'karate-minsalary@example.com', salary: 30000 }
    When method post
    Then status 201
    And match response.salary == 30000.0
    * def minSalaryId = response.id

    # Cleanup
    Given url employeesUrl + '/' + minSalaryId
    When method delete
    Then status 204
