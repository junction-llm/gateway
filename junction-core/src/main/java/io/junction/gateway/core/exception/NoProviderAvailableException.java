package io.junction.gateway.core.exception;

public class NoProviderAvailableException extends RuntimeException {
    public NoProviderAvailableException() {
        super("No available providers found");
    }
}