package com.spring.problemdetails.exception;

public class InvalidSalaryException extends RuntimeException {
    private final double currentSalary;
    private final double proposedSalary;

    public InvalidSalaryException(String message, double currentSalary, double proposedSalary) {
        super(message);
        this.currentSalary = currentSalary;
        this.proposedSalary = proposedSalary;
    }

    public double getCurrentSalary() {
        return currentSalary;
    }

    public double getProposedSalary() {
        return proposedSalary;
    }
}
