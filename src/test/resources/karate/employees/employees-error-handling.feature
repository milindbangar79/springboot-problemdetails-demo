Feature: Employee API - RFC 9457 ProblemDetail Error Handling E2E

  Background:
    * def employeesUrl = baseUrl + '/employees'

  # ─── 404 Resource Not Found ──────────────────────────────────────────────────

  Scenario: GET non-existent employee returns 404 ProblemDetail
    Given url employeesUrl + '/99999'
    When method get
    Then status 404
    And match header Content-Type contains 'application/problem+json'
    And match response.status == 404
    And match response.title == 'Resource Not Found'
    And match response.detail contains '99999'
    And match response.error_code == 'ERR_RES_404'
    And match response.timestamp == '#notnull'
    And match response.type contains 'resource-not-found'

  Scenario: PUT non-existent employee returns 404 ProblemDetail
    Given url employeesUrl + '/99999'
    And request { name: 'Ghost', department: 'Unknown', email: 'ghost-put@example.com', salary: 50000 }
    When method put
    Then status 404
    And match header Content-Type contains 'application/problem+json'
    And match response.status == 404
    And match response.title == 'Resource Not Found'
    And match response.error_code == 'ERR_RES_404'

  Scenario: DELETE non-existent employee returns 404 ProblemDetail
    Given url employeesUrl + '/99999'
    When method delete
    Then status 404
    And match header Content-Type contains 'application/problem+json'
    And match response.status == 404
    And match response.error_code == 'ERR_RES_404'

  # ─── 409 Duplicate Resource ──────────────────────────────────────────────────

  Scenario: POST with duplicate email returns 409 ProblemDetail
    Given url employeesUrl
    And request { name: 'Alice Duplicate', department: 'Engineering', email: 'alice@example.com', salary: 80000 }
    When method post
    Then status 409
    And match header Content-Type contains 'application/problem+json'
    And match response.status == 409
    And match response.title == 'Duplicate Resource'
    And match response.detail contains 'alice@example.com'
    And match response.error_code == 'ERR_DUPLICATE_409'
    And match response.timestamp == '#notnull'
    And match response.type contains 'duplicate-resource'

  # ─── 422 Business Rule Violation (salary reduction > 10%) ───────────────────

  Scenario: PUT with salary reduction over 10 percent returns 422 ProblemDetail
    # Create a dedicated employee so this scenario owns its state
    Given url employeesUrl
    And request { name: 'Karate Salary Test', department: 'Finance', email: 'karate-salary@example.com', salary: 80000 }
    When method post
    Then status 201
    * def empId = response.id

    # Attempt to reduce salary by more than 10% (80000 * 0.9 = 72000; set 50000 < 72000)
    Given url employeesUrl + '/' + empId
    And request { name: 'Karate Salary Test', department: 'Finance', email: 'karate-salary@example.com', salary: 50000 }
    When method put
    Then status 422
    And match header Content-Type contains 'application/problem+json'
    And match response.status == 422
    And match response.title == 'Business Rule Violation'
    And match response.error_code == 'ERR_SALARY_422'
    And match response.current_salary == '#number'
    And match response.proposed_salary == 50000.0
    And match response.timestamp == '#notnull'
    And match response.type contains 'business-rule-violation'

    # Cleanup
    Given url employeesUrl + '/' + empId
    When method delete
    Then status 204

  # ─── 400 Bean Validation ─────────────────────────────────────────────────────

  Scenario: POST with blank fields and negative salary returns 400 ProblemDetail
    Given url employeesUrl
    And request { name: '', department: '', email: 'not-valid-email', salary: -500 }
    When method post
    Then status 400
    And match header Content-Type contains 'application/problem+json'
    And match response.status == 400

  Scenario: POST with salary below minimum returns 400 ProblemDetail
    Given url employeesUrl
    And request { name: 'Low Salary', department: 'Finance', email: 'low-salary@example.com', salary: 100 }
    When method post
    Then status 400
    And match header Content-Type contains 'application/problem+json'
    And match response.status == 400

  Scenario: PUT with invalid body returns 400 ProblemDetail
    Given url employeesUrl + '/2'
    And request { name: '', department: '', email: 'bad-email', salary: -1 }
    When method put
    Then status 400
    And match header Content-Type contains 'application/problem+json'
    And match response.status == 400
