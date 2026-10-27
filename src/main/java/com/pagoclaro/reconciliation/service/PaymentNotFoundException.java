package com.pagoclaro.reconciliation.service;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException() {
        super("payment not found");
    }
}
