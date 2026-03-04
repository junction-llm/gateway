package io.junction.gateway.core.exception;

import io.junction.gateway.core.security.ApiKeyValidator;

/**
 * Exception thrown when API key authentication fails.
 * 
 * <p>Includes HTTP status code and error details for proper error responses.
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
public class ApiKeyAuthenticationException extends RuntimeException {
    
    private final int httpStatus;
    private final String errorCode;
    private final ApiKeyValidator.ValidationError validationError;
    
    public ApiKeyAuthenticationException(String message, int httpStatus, String errorCode) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.validationError = null;
    }
    
    public ApiKeyAuthenticationException(ApiKeyValidator.ValidationResult result) {
        super(result.errorMessage());
        this.httpStatus = result.httpStatus();
        this.errorCode = result.error() != null ? result.error().name().toLowerCase() : "unknown_error";
        this.validationError = result.error();
    }
    
    public int getHttpStatus() {
        return httpStatus;
    }
    
    public String getErrorCode() {
        return errorCode;
    }
    
    public ApiKeyValidator.ValidationError getValidationError() {
        return validationError;
    }
    
    public ErrorResponse toErrorResponse() {
        return new ErrorResponse(getMessage(), errorCode, String.valueOf(httpStatus));
    }
    
    public record ErrorResponse(String message, String type, String code) {}
}
