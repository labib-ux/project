package com.nagorikseba.complaint.routing;

/**
 * No active department can take this complaint (§7.2 fallback).
 *
 * <p>Extends {@code IllegalArgumentException} so the existing 400 mapper
 * explains the failure without touching {@code GlobalExceptionHandler}. The
 * complaint itself is untouched (the transaction rolls back), so it stays
 * VERIFIED with its history intact — a human can assign it manually later.
 */
public class NoEligibleDepartmentException extends IllegalArgumentException {
    public NoEligibleDepartmentException(String message) {
        super(message);
    }
}
