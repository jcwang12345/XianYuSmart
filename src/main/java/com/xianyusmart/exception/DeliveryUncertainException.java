package com.xianyusmart.exception;

/** The request may have reached the platform. It must not be automatically resent. */
public class DeliveryUncertainException extends RuntimeException {
    public DeliveryUncertainException(String message) { super(message); }
}
