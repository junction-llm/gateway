package io.junction.gateway.core.exception;

public class ProviderException extends RuntimeException {
    private final int code;
    
    public ProviderException(String message, int code) {
        super(message);
        this.code = code;
    }
    
    public int getCode() {
        return code;
    }
}