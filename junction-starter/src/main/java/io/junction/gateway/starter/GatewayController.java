package io.junction.gateway.starter;

import io.junction.gateway.core.cache.ModelCacheService;
import io.junction.gateway.core.context.RequestContext;
import io.junction.gateway.core.exception.ApiKeyAuthenticationException;
import io.junction.gateway.core.exception.IpRateLimitExceededException;
import io.junction.gateway.core.exception.NoProviderAvailableException;
import io.junction.gateway.core.exception.ProviderException;
import io.junction.gateway.core.exception.RouterException;
import io.junction.gateway.core.model.*;
import io.junction.gateway.core.router.Router;
import io.junction.gateway.core.security.ApiKeyValidator;
import io.junction.gateway.core.security.IpRateLimiter;
import io.junction.gateway.starter.clientcompat.ClientAdapterConfig;
import io.junction.gateway.starter.clientcompat.ClientCompatibilityService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Enumeration;
import java.util.List;

/**
 * OpenAI-compatible API Gateway Controller.
 * 
 * <p>Provides chat completion endpoints with:
 * <ul>
 *   <li>API key authentication and validation</li>
 *   <li>Rate limiting per key</li>
 *   <li>Streaming (SSE) and non-streaming (JSON) responses</li>
 *   <li>Client compatibility patches</li>
 *   <li>Virtual thread-based request handling</li>
 * </ul>
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
@RestController
@RequestMapping("/v1")
public class GatewayController {
    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);
    private static final List<String> SENSITIVE_HEADERS = List.of(
        "authorization",
        "x-api-key",
        "cookie",
        "set-cookie",
        "proxy-authorization"
    );
    
    private final Router router;
    private final JsonMapper jsonMapper;
    private final ClientCompatibilityService clientCompatService;
    private final ApiKeyValidator apiKeyValidator;
    private final IpRateLimiter ipRateLimiter;
    private final JunctionProperties junctionProperties;
    private final ModelCacheService modelCacheService;
    
    @Autowired
    public GatewayController(Router router, 
                           JsonMapper jsonMapper, 
                           ClientCompatibilityService clientCompatService,
                           ApiKeyValidator apiKeyValidator,
                           IpRateLimiter ipRateLimiter,
                           JunctionProperties junctionProperties,
                           ModelCacheService modelCacheService) {
        this.router = router;
        this.jsonMapper = jsonMapper;
        this.clientCompatService = clientCompatService;
        this.apiKeyValidator = apiKeyValidator;
        this.ipRateLimiter = ipRateLimiter;
        this.junctionProperties = junctionProperties;
        this.modelCacheService = modelCacheService;
    }
    
    /**
     * Streaming chat completions endpoint (SSE).
     * 
     * @param request the chat completion request payload
     * @param acceptHeader the Accept header to determine response type
     * @param userAgent the User-Agent header for logging and client detection
     * @param httpServletRequest the raw HTTP servlet request for client detection and logging
     * @return an SseEmitter that streams chat completion chunks in OpenAI SSE format
     */
    @PostMapping(value = "/chat/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatCompletionsStreaming(
            @RequestBody ChatCompletionRequest request,
            @RequestHeader(value = "Accept", required = false) String acceptHeader,
            @RequestHeader(value = "User-Agent", required = false) String userAgent,
            HttpServletRequest httpServletRequest) {
        
        var traceId = java.util.UUID.randomUUID();
        var resolvedApiKey = resolveApiKey(httpServletRequest);
        
        var ctx = new RequestContext.Context(
            traceId,
            resolvedApiKey.apiKey(),
            request.model(),
            java.time.Instant.now()
        );

        ApiKeyValidator.ValidationResult validation;
        try (var ignored = MDC.putCloseable("traceId", traceId.toString())) {
            ScopedValue.where(RequestContext.key(), ctx).run(() -> {
                logRequestDetails(request, resolvedApiKey, acceptHeader, userAgent, httpServletRequest, "SSE", traceId);
            });

            validation = ScopedValue.where(RequestContext.key(), ctx).call(() -> {
                return validateApiKey(resolvedApiKey.apiKey(), httpServletRequest, request.model());
            });
        }
        
        if (!validation.valid()) {
            throw new ApiKeyAuthenticationException(validation);
        }
        
        if (!request.stream()) {
            log.warn("SSE endpoint called with stream=false - overriding to stream=true");
        }
        
        return createSseEmitter(request, httpServletRequest, traceId, ctx);
    }
    
    @PostMapping(value = "/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object chatCompletionsJson(
            @RequestBody ChatCompletionRequest request,
            @RequestHeader(value = "Accept", required = false) String acceptHeader,
            @RequestHeader(value = "User-Agent", required = false) String userAgent,
            HttpServletRequest httpServletRequest) {
        
        var traceId = java.util.UUID.randomUUID();
        var resolvedApiKey = resolveApiKey(httpServletRequest);
        
        var ctx = new RequestContext.Context(
            traceId,
            resolvedApiKey.apiKey(),
            request.model(),
            java.time.Instant.now()
        );

        ApiKeyValidator.ValidationResult validation;
        try (var ignored = MDC.putCloseable("traceId", traceId.toString())) {
            ScopedValue.where(RequestContext.key(), ctx).run(() -> {
                logRequestDetails(request, resolvedApiKey, acceptHeader, userAgent, httpServletRequest, "JSON", traceId);
            });

            validation = ScopedValue.where(RequestContext.key(), ctx).call(() -> {
                return validateApiKey(resolvedApiKey.apiKey(), httpServletRequest, request.model());
            });
        }
        
        if (!validation.valid()) {
            throw new ApiKeyAuthenticationException(validation);
        }
        
        if (request.stream()) {
            try (var ignored = MDC.putCloseable("traceId", traceId.toString())) {
                log.info("[{}] JSON endpoint called with stream=true - switching to streaming mode", traceId);
            }
            return createSseEmitter(request, httpServletRequest, traceId, ctx);
        }
        
        try (var ignored = MDC.putCloseable("traceId", traceId.toString())) {
            ChatCompletionResponse response = createJsonResponse(request, httpServletRequest, traceId, ctx);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response);
        } catch (Exception e) {
            log.error("[{}] Error processing non-streaming request", traceId, e);
            throw e;
        }
    }
    
    /**
     * Validates the API key for the request and checks IP restrictions.
     * 
     * @param apiKey the API key to validate
     * @param request the HTTP servlet request for client IP extraction
     * @param model the requested model for potential tier-based validation
     * @return the validation result indicating if the key is valid and any error details
     */
    private ApiKeyValidator.ValidationResult validateApiKey(String apiKey, 
                                                            HttpServletRequest request,
                                                            String model) {
        String clientIp = getClientIp(request);
        
        if (!isIpAllowed(clientIp)) {
            log.warn("IP {} is not in whitelist", clientIp);
            return ApiKeyValidator.ValidationResult.failure(
                ApiKeyValidator.ValidationError.IP_NOT_ALLOWED,
                "Access denied from IP: " + clientIp
            );
        }
        
        var ipRateResult = ipRateLimiter.checkAndIncrement(clientIp);
        if (!ipRateResult.isAllowed()) {
            log.warn("IP {} exceeded rate limit: {}", clientIp, ipRateResult.rejectionReason());
            throw new IpRateLimitExceededException(clientIp, ipRateResult);
        }
        
        return apiKeyValidator.validate(apiKey, clientIp, model);
    }
    
    /**
     * Checks if the IP address is allowed based on whitelist configuration.
     * 
     * @param clientIp the IP address of the client making the request
     * @return true if the IP is allowed, false otherwise
     */
    private boolean isIpAllowed(String clientIp) {
        var whitelistConfig = junctionProperties.getSecurity().getIpWhitelist();
        
        if (!whitelistConfig.isEnabled()) {
            return true;
        }
        
        if (whitelistConfig.isAllowPrivateIps() && isPrivateIp(clientIp)) {
            return true;
        }
        
        String allowedIps = whitelistConfig.getAllowedIps();
        if (allowedIps == null || allowedIps.isBlank()) {
            return true;
        }
        
        String[] allowed = allowedIps.split(",");
        for (String allowedIp : allowed) {
            String ip = allowedIp.trim();
            if (ip.isEmpty()) continue;
            
            if (ip.equals(clientIp)) {
                return true;
            }
            
            if (ip.contains("/")) {
                if (isIpInCidrRange(clientIp, ip)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Checks if an IP is in a CIDR range.
     * 
     * @param ip the IP address to check
     * @param cidr the CIDR range to check against (e.g. "
     * @return true if the IP is in the range, false otherwise
     */
    private boolean isIpInCidrRange(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            String network = parts[0];
            int prefixLength = Integer.parseInt(parts[1]);
            
            byte[] ipBytes = ipToBytes(ip);
            byte[] networkBytes = ipToBytes(network);
            
            if (ipBytes == null || networkBytes == null) {
                return false;
            }
            
            int mask = 0xFFFFFFFF << (32 - prefixLength);
            
            int ipInt = ((ipBytes[0] & 0xFF) << 24) |
                       ((ipBytes[1] & 0xFF) << 16) |
                       ((ipBytes[2] & 0xFF) << 8) |
                       (ipBytes[3] & 0xFF);
                       
            int networkInt = ((networkBytes[0] & 0xFF) << 24) |
                            ((networkBytes[1] & 0xFF) << 16) |
                            ((networkBytes[2] & 0xFF) << 8) |
                            (networkBytes[3] & 0xFF);
            
            return (ipInt & mask) == (networkInt & mask);
        } catch (Exception e) {
            log.warn("Error checking CIDR range {} for IP {}: {}", cidr, ip, e.getMessage());
            return false;
        }
    }
    
    /**
     * Converts IP address string to byte array.
     * 
     * @param ip the IP address in string format (e.g. "
     * @return byte array representation of the IP address, or null if invalid
     */
    private byte[] ipToBytes(String ip) {
        try {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) {
                return null;
            }
            byte[] bytes = new byte[4];
            for (int i = 0; i < 4; i++) {
                bytes[i] = (byte) Integer.parseInt(parts[i]);
            }
            return bytes;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Checks if an IP is a private/internal IP.
     * 
     * @param ip the IP address to check
     * @return true if the IP is private, false otherwise
     */
    private boolean isPrivateIp(String ip) {
        if (ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) {
            return true;
        }
        if (ip.startsWith("172.")) {
            try {
                int secondOctet = Integer.parseInt(ip.split("\\.")[1]);
                return secondOctet >= 16 && secondOctet <= 31;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }
    
    /**
     * Extracts the client IP address from the request.
     * 
     * @param request the HTTP servlet request
     * @return the client IP address as a string
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp;
        }
        
        return request.getRemoteAddr();
    }
    
    private void logRequestDetails(ChatCompletionRequest request, ResolvedApiKey resolvedApiKey, String acceptHeader,
                                    String userAgent, HttpServletRequest httpServletRequest, String endpointType, java.util.UUID traceId) {
        log.info("[{}] === INCOMING REQUEST [{}] ===", traceId, endpointType);
        log.info("[{}] Client IP: {}", traceId, getClientIp(httpServletRequest));
        log.info("[{}] Request URI: {} {}", traceId, httpServletRequest.getMethod(), httpServletRequest.getRequestURI());
        log.info("[{}] Request Query String: {}", traceId, httpServletRequest.getQueryString());
        log.info("[{}] Request Model: {}", traceId, request.model());
        log.info("[{}] Request Stream: {}", traceId, request.stream());
        log.info("[{}] Request Temperature: {}", traceId, request.temperature());
        log.info("[{}] Request Messages Count: {}", traceId, request.messages() != null ? request.messages().size() : "null");
        log.info("[{}] Accept Header: {}", traceId, acceptHeader);
        log.info("[{}] User-Agent Header: {}", traceId, userAgent);
        log.info("[{}] Authentication Source: {}", traceId, resolvedApiKey.source());
        log.info("[{}] === ===", traceId);
        
        
        log.debug("=== CLIENT DETECTION HEADERS ===");
        String[] clientHeaders = {
            "x-client-name",
            "x-client-version", 
            "x-llm-model",
            "x-request-id"
        };
        for (String headerName : clientHeaders) {
            String value = httpServletRequest.getHeader(headerName);
            if (value != null) {
                log.debug("Client Header - {}: {}", headerName, value);
            }
        }
        
        
        log.debug("=== ALL HEADERS ===");
        Enumeration<String> headerNames = httpServletRequest.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            log.debug("Header - {}: {}", headerName, redactHeaderValue(headerName, httpServletRequest.getHeader(headerName)));
        }
        log.debug("===================");
    }
    
    private SseEmitter createSseEmitter(ChatCompletionRequest request, 
                                        HttpServletRequest httpRequest,
                                        java.util.UUID traceId,
                                        RequestContext.Context ctx) {
        var emitter = new SseEmitter(0L); 

        ClientAdapterConfig adapterConfig;
        ChatCompletionRequest patchedRequest;
        try (var ignored = MDC.putCloseable("traceId", traceId.toString())) {
            log.info("[{}] Starting SSE stream for model: {}", traceId, request.model());

            
            adapterConfig = clientCompatService.detectClient(httpRequest);
            if (adapterConfig != null) {
                log.info("[{}] Detected client adapter: {}", traceId, adapterConfig.getId());
            }
            patchedRequest = clientCompatService.applyRequestPatches(request, adapterConfig);
        }
        
        Thread.ofVirtual().start(() -> {
            try (var ignored = MDC.putCloseable("traceId", traceId.toString())) {
                ScopedValue.where(RequestContext.key(), ctx).run(() -> {
                    try {
                        log.info("[{}] Routing request", traceId);
                        var provider = router.route(patchedRequest);
                        log.info("[{}] Routed to provider: {}", traceId, provider.providerId());
                        
                        var gatherer = provider.responseAdapter();
                        int chunkCount = 0;
                        
                        log.info("[{}] Processing response stream", traceId);
                        try (var stream = provider.execute(patchedRequest).gather(gatherer)) {
                            var iterator = stream.iterator();
                            while (iterator.hasNext()) {
                                var chunk = iterator.next();
                                try {
                                    if (adapterConfig != null) {
                                        chunk = clientCompatService.applyResponsePatches(chunk, adapterConfig);
                                    }

                                    String jsonChunk = jsonMapper.writeValueAsString(chunk);
                                    emitter.send(SseEmitter.event().data(jsonChunk));
                                    chunkCount++;

                                    boolean isDone = chunk.choices() != null
                                        && !chunk.choices().isEmpty()
                                        && chunk.choices().get(0).finishReason() != null;

                                    if (chunkCount == 1 || chunkCount % 60 == 0 || isDone) {
                                        log.debug("[{}] Sending chunk #{} ({} bytes)", traceId, chunkCount, jsonChunk.length());
                                    }
                                } catch (IOException e) {
                                    log.error("[{}] IOException sending chunk: {}", traceId, e.getMessage());
                                    emitter.completeWithError(e);
                                    return;
                                }
                            }
                        }

                        
                        log.info("[{}] Sending [DONE] marker", traceId);
                        emitter.send(SseEmitter.event()
                            .data("[DONE]"));
                        
                        log.info("[{}] SSE stream completed successfully, sent {} chunks", traceId, chunkCount);
                        emitter.complete();
                        
                    } catch (ProviderException e) {
                        log.error("[{}] Provider error: {} (code: {})", traceId, e.getMessage(), e.getCode());
                        sendErrorEvent(emitter, e.getCode(), e.getMessage());
                        emitter.complete();
                    } catch (NoProviderAvailableException e) {
                        log.error("[{}] No provider available", traceId);
                        sendErrorEvent(emitter, 503, "No provider available");
                        emitter.complete();
                    } catch (Exception e) {
                        log.error("[{}] Unexpected error in SSE stream", traceId, e);
                        sendErrorEvent(emitter, 500, "Internal server error: " + e.getMessage());
                        emitter.complete();
                    }
                });
            }
        });
        
        return emitter;
    }
    
    private void sendErrorEvent(SseEmitter emitter, int code, String message) {
        try {
            
            String errorJson = String.format("{\"error\": {\"message\": \"%s\", \"type\": \"api_error\", \"code\": %d}}", 
                    message.replace("\"", "\\\""), code);
            emitter.send(SseEmitter.event()
                .data(errorJson));
        } catch (IOException e) {
            log.error("Failed to send error event: {}", e.getMessage());
        }
    }
    
    private ChatCompletionResponse createJsonResponse(ChatCompletionRequest request,
                                                      HttpServletRequest httpRequest,
                                                      java.util.UUID traceId,
                                                      RequestContext.Context ctx) {
        try (var ignored = MDC.putCloseable("traceId", traceId.toString())) {
            return ScopedValue.where(RequestContext.key(), ctx).call(() -> {
                ClientAdapterConfig adapterConfig = clientCompatService.detectClient(httpRequest);
                if (adapterConfig != null) {
                    log.info("[{}] Detected client adapter: {}", traceId, adapterConfig.getId());
                }

                ChatCompletionRequest patchedRequest = clientCompatService.applyRequestPatches(request, adapterConfig);
                var provider = router.route(patchedRequest);
                var gatherer = provider.responseAdapter();
                
                StringBuilder contentBuilder = new StringBuilder();
                String[] modelHolder = { patchedRequest.model() };
                java.util.List<ChatCompletionChunk.ToolCall> toolCallsHolder = new java.util.ArrayList<>();
                
                try (var stream = provider.execute(patchedRequest).gather(gatherer)) {
                    stream.forEach(chunk -> {
                        if (adapterConfig != null) {
                            chunk = clientCompatService.applyResponsePatches(chunk, adapterConfig);
                        }
                        if (chunk.choices() != null && !chunk.choices().isEmpty()) {
                            var choice = chunk.choices().get(0);
                            if (choice.delta().content() != null) {
                                contentBuilder.append(choice.delta().content());
                            }
                            if (choice.delta().toolCalls() != null) {
                                toolCallsHolder.addAll(choice.delta().toolCalls());
                            }
                        }
                        if (chunk.model() != null) {
                            modelHolder[0] = chunk.model();
                        }
                    });
                }

                
                java.util.List<ChatCompletionResponse.ToolCall> responseToolCalls = null;
                if (!toolCallsHolder.isEmpty()) {
                    responseToolCalls = new java.util.ArrayList<>();
                    for (var tc : toolCallsHolder) {
                        responseToolCalls.add(new ChatCompletionResponse.ToolCall(
                            tc.index(),
                            tc.id(),
                            tc.type(),
                            new ChatCompletionResponse.Function(
                                tc.function().name(),
                                tc.function().arguments()
                            )
                        ));
                    }
                }
                
                ChatCompletionResponse response = ChatCompletionResponse.complete(
                    modelHolder[0], 
                    contentBuilder.toString(), 
                    responseToolCalls
                );
                log.debug("[{}] Returning JSON response", ctx.traceId());
                return response;
            });
        }
    }

    private String redactHeaderValue(String headerName, String value) {
        if (value == null) {
            return null;
        }
        if (SENSITIVE_HEADERS.contains(headerName.toLowerCase())) {
            return "<redacted>";
        }
        return value;
    }
    
    /**
     * Lists available models (OpenAI-compatible).
     * 
     * <p>GET /v1/models returns a list of models available through the gateway.
     * 
     * <p>API key authentication is required. Models are cached for 24 hours to avoid
     * hitting provider APIs on every request.
     * 
     * @param request the HTTP servlet request for client IP extraction
     * @return a ModelList containing all available models from enabled providers
     * 
     * @since 0.0.2
     */
    @GetMapping("/models")
    public ResponseEntity<ModelList> listModels(
            HttpServletRequest request) {
        var resolvedApiKey = resolveApiKey(request);
        
        var clientIp = getClientIp(request);
        
        var validation = validateApiKey(resolvedApiKey.apiKey(), request, null);
        if (!validation.valid()) {
            throw new ApiKeyAuthenticationException(validation);
        }
        
        var ipRateResult = ipRateLimiter.checkAndIncrement(clientIp);
        if (!ipRateResult.isAllowed()) {
            log.warn("IP {} exceeded rate limit for /models endpoint", clientIp);
            throw new IpRateLimitExceededException(clientIp, ipRateResult);
        }
        
        var models = new java.util.ArrayList<ModelInfo>();
        
        var allProviders = router.getProviders();
        
        if (junctionProperties.getOllama().isEnabled()) {
            var ollamaModels = modelCacheService.getModels(
                "ollama",
                "Ollama",
                () -> allProviders.stream()
                    .filter(p -> p.providerId().equals("ollama"))
                    .findFirst()
                    .map(p -> (io.junction.gateway.core.provider.OllamaProvider) p)
                    .map(io.junction.gateway.core.provider.OllamaProvider::getAvailableModels)
                    .orElse(java.util.List.of())
            );
            models.addAll(ollamaModels);
        }
        
        if (junctionProperties.getGemini().isEnabled()) {
            var geminiModels = modelCacheService.getModels(
                "gemini",
                "Gemini",
                () -> allProviders.stream()
                    .filter(p -> p.providerId().equals("gemini"))
                    .findFirst()
                    .map(p -> (io.junction.gateway.core.provider.GeminiProvider) p)
                    .map(io.junction.gateway.core.provider.GeminiProvider::getAvailableModels)
                    .orElse(java.util.List.of())
            );
            models.addAll(geminiModels);
        }
        
        return ResponseEntity.ok(ModelList.of(models));
    }
    
    
    
    @ExceptionHandler(ApiKeyAuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleApiKeyAuthentication(ApiKeyAuthenticationException e) {
        log.warn("API key authentication failed: {}", e.getMessage());
        return ResponseEntity.status(e.getHttpStatus())
                .body(new ErrorResponse(e.getMessage(), e.getErrorCode(), e.getHttpStatus()));
    }
    
    @ExceptionHandler(IpRateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleIpRateLimitExceeded(IpRateLimitExceededException e) {
        log.warn("IP rate limit exceeded for {}: {}", e.getIpAddress(), e.getMessage());
        long retryAfter = e.getRetryAfter() - System.currentTimeMillis() / 1000;
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(Math.max(1, retryAfter)))
                .body(new ErrorResponse(e.getMessage(), "rate_limit_exceeded", 429));
    }
    
    @ExceptionHandler(ProviderException.class)
    public ResponseEntity<ErrorResponse> handleProviderException(ProviderException e) {
        log.error("ProviderException: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse(e.getMessage(), "provider_error", e.getCode()));
    }
    
    @ExceptionHandler(NoProviderAvailableException.class)
    public ResponseEntity<ErrorResponse> handleNoProviderAvailable(NoProviderAvailableException e) {
        log.error("NoProviderAvailableException: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("No provider available", "service_unavailable", 503));
    }
    
    @ExceptionHandler(RouterException.class)
    public ResponseEntity<ErrorResponse> handleRouterException(RouterException e) {
        log.error("RouterException: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(e.getMessage(), "invalid_request", 400));
    }
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception e) {
        log.error("Unexpected error: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Internal server error", "internal_error", 500));
    }
    
    /**
     * OpenAI-compatible error response wrapper
     */
    public record ErrorResponse(Error error) {
        public ErrorResponse(String message, String type, String code) {
            this(new Error(message, type, code));
        }
        
        public ErrorResponse(String message, String type, int code) {
            this(new Error(message, type, String.valueOf(code)));
        }
        
        public record Error(String message, String type, String code) {}
    }

    private ResolvedApiKey resolveApiKey(HttpServletRequest request) {
        String xApiKey = normalizeCredential(request.getHeader("X-API-Key"));
        String bearerApiKey = extractBearerApiKey(request.getHeader("Authorization"));

        if (xApiKey != null && bearerApiKey != null) {
            if (!xApiKey.equals(bearerApiKey)) {
                throw new ApiKeyAuthenticationException(
                    "Conflicting API credentials provided in X-API-Key and Authorization headers.",
                    HttpStatus.UNAUTHORIZED.value(),
                    "invalid_key"
                );
            }
            return new ResolvedApiKey(xApiKey, "both");
        }

        if (xApiKey != null) {
            return new ResolvedApiKey(xApiKey, "x-api-key");
        }

        if (bearerApiKey != null) {
            return new ResolvedApiKey(bearerApiKey, "authorization");
        }

        return new ResolvedApiKey(null, "absent");
    }

    private String extractBearerApiKey(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }

        int separatorIndex = authorizationHeader.indexOf(' ');
        if (separatorIndex <= 0) {
            return null;
        }

        String scheme = authorizationHeader.substring(0, separatorIndex);
        if (!scheme.equalsIgnoreCase("Bearer")) {
            return null;
        }

        return normalizeCredential(authorizationHeader.substring(separatorIndex + 1));
    }

    private String normalizeCredential(String credential) {
        if (credential == null) {
            return null;
        }

        String trimmed = credential.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private record ResolvedApiKey(String apiKey, String source) {}
    
}
