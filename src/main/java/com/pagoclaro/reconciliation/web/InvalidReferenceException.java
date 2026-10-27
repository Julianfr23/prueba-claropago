package com.pagoclaro.reconciliation.web;

public class InvalidReferenceException extends RuntimeException {

    public InvalidReferenceException() {
        super("invalid payment reference");
    }
}
