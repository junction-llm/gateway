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
import io.junction.gateway.core.tracing.GatewayTracing;
import io.junction.gateway.starter.clientcompat.ClientAdapterConfig;
import io.junction.gateway.starter.clientcompat.ClientCompatibilityService;
import io.junction.gateway.starter.observability.JunctionObservabilityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Instant;
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
    private static final Logger responsePayloadLog = LoggerFactory.getLogger("io.junction.gateway.payload.chat.response");
    private static final String REQUEST_TRACKER_ATTRIBUTE = GatewayController.class.getName() + ".requestTracker";
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
    private final JunctionObservabilityService observabilityService;
    private final GatewayTracing gatewayTracing;
    
    @Autowired
    public GatewayController(Router router, 
                           JsonMapper jsonMapper, 
                           ClientCompatibilityService clientCompatService,
                           ApiKeyValidator apiKeyValidator,
                           IpRateLimiter ipRateLimiter,
                           JunctionProperties junctionProperties,
                           ModelCacheService modelCacheService,
                           JunctionObservabilityService observabilityService,
                           GatewayTracing gatewayTracing) {
        this.router = router;
        this.jsonMapper = jsonMapper;
        this.clientCompatService = clientCompatService;
        this.apiKeyValidator = apiKeyValidator;
        this.ipRateLimiter = ipRateLimiter;
        this.junctionProperties = junctionProperties;
        this.modelCacheService = modelCacheService;
        this.observabilityService = observabilityService;
        this.gatewayTracing = gatewayTracing;
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
            @RequestHeader(value = "X-Provider", required = false) String providerHeader,
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse) {
        
        var traceId = java.util.UUID.randomUUID();
        var requestParentTraceContext = gatewayTracing.fromIncomingHeaders(
            httpServletRequest.getHeader("traceparent"),
            httpServletRequest.getHeader("tracestate"),
            httpServletRequest.getHeader("baggage")
        );
        setTraceHeader(httpServletResponse, traceId);
        var tracker = registerRequestTracker(httpServletRequest, "chat", "sse");
        var resolvedApiKey = resolveApiKey(httpServletRequest);
        tracker.authSource(resolvedApiKey.source());
        var preferredProvider = resolveRequestedProvider(providerHeader);

        RequestContext.Context ctx;
        ApiKeyValidator.ValidationResult validation;
        try (var requestSpan = gatewayTracing.startSpan("junction.gateway.request", requestParentTraceContext)) {
            requestSpan.tag("junction.endpoint", "chat");
            requestSpan.tag("junction.response_mode", "sse");
            ctx = createRequestContext(traceId, resolvedApiKey.apiKey(), request.model(), httpServletRequest, requestSpan.trace());

            try (var ignored = openLoggingContext(traceId, ctx)) {
                ScopedValue.where(RequestContext.key(), ctx).run(() -> {
                    logRequestDetails(request, resolvedApiKey, preferredProvider, acceptHeader, userAgent, httpServletRequest, "SSE", traceId);
                });

                validation = ScopedValue.where(RequestContext.key(), ctx).call(() -> {
                    return validateApiKey(resolvedApiKey.apiKey(), httpServletRequest, request.model());
                });
            }
        }
        
        if (!validation.valid()) {
            recordAuthFailure(validation);
            tracker.finishFailure(validation.error().name());
            throw new ApiKeyAuthenticationException(validation);
        }
        
        if (!request.stream()) {
            log.warn("SSE endpoint called with stream=false - overriding to stream=true");
        }
        
        return createSseEmitter(request, httpServletRequest, traceId, ctx, preferredProvider, tracker, requestParentTraceContext);
    }
    
    @PostMapping(value = "/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object chatCompletionsJson(
            @RequestBody ChatCompletionRequest request,
            @RequestHeader(value = "Accept", required = false) String acceptHeader,
            @RequestHeader(value = "User-Agent", required = false) String userAgent,
            @RequestHeader(value = "X-Provider", required = false) String providerHeader,
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse) {
        
        var traceId = java.util.UUID.randomUUID();
        var requestParentTraceContext = gatewayTracing.fromIncomingHeaders(
            httpServletRequest.getHeader("traceparent"),
            httpServletRequest.getHeader("tracestate"),
            httpServletRequest.getHeader("baggage")
        );
        setTraceHeader(httpServletResponse, traceId);
        var tracker = registerRequestTracker(httpServletRequest, "chat", "json");
        var resolvedApiKey = resolveApiKey(httpServletRequest);
        tracker.authSource(resolvedApiKey.source());
        var preferredProvider = resolveRequestedProvider(providerHeader);

        try (var requestSpan = gatewayTracing.startSpan("junction.gateway.request", requestParentTraceContext)) {
            requestSpan.tag("junction.endpoint", "chat");
            requestSpan.tag("junction.response_mode", "json");

            var ctx = createRequestContext(traceId, resolvedApiKey.apiKey(), request.model(), httpServletRequest, requestSpan.trace());

            ApiKeyValidator.ValidationResult validation;
            try (var ignored = openLoggingContext(traceId, ctx)) {
                ScopedValue.where(RequestContext.key(), ctx).run(() -> {
                    logRequestDetails(request, resolvedApiKey, preferredProvider, acceptHeader, userAgent, httpServletRequest, "JSON", traceId);
                });

                validation = ScopedValue.where(RequestContext.key(), ctx).call(() -> {
                    return validateApiKey(resolvedApiKey.apiKey(), httpServletRequest, request.model());
                });
            }

            if (!validation.valid()) {
                recordAuthFailure(validation);
                tracker.finishFailure(validation.error().name());
                throw new ApiKeyAuthenticationException(validation);
            }

            if (request.stream()) {
                try (var ignored = openLoggingContext(traceId, ctx)) {
                    log.info("[{}] JSON endpoint called with stream=true - switching to streaming mode", traceId);
                }
                return createSseEmitter(request, httpServletRequest, traceId, ctx, preferredProvider, tracker, requestParentTraceContext);
            }

            try (var ignored = openLoggingContext(traceId, ctx)) {
                ChatCompletionResponse response = createJsonResponse(request, httpServletRequest, traceId, ctx, preferredProvider, tracker);
                tracker.finishSuccess();
                return ResponseEntity.ok()
                        .header("X-Trace-ID", traceId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(response);
            } catch (Exception e) {
                if (!tracker.isFinished()) {
                    tracker.finishFailure("internal_error");
                }
                log.error("[{}] Error processing non-streaming request", traceId, e);
                throw e;
            }
        }
    }

    @PostMapping(value = "/embeddings", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EmbeddingResponse> embeddings(
            @RequestBody EmbeddingRequest request,
            @RequestHeader(value = "Accept", required = false) String acceptHeader,
            @RequestHeader(value = "User-Agent", required = false) String userAgent,
            HttpServletRequest httpServletRequest,
            HttpServletResponse httpServletResponse) {

        var traceId = java.util.UUID.randomUUID();
        var requestParentTraceContext = gatewayTracing.fromIncomingHeaders(
            httpServletRequest.getHeader("traceparent"),
            httpServletRequest.getHeader("tracestate"),
            httpServletRequest.getHeader("baggage")
        );
        setTraceHeader(httpServletResponse, traceId);
        var tracker = registerRequestTracker(httpServletRequest, "embeddings", "json");
        var resolvedApiKey = resolveApiKey(httpServletRequest);
        tracker.authSource(resolvedApiKey.source());

        try (var requestSpan = gatewayTracing.startSpan("junction.gateway.request", requestParentTraceContext)) {
            requestSpan.tag("junction.endpoint", "embeddings");
            requestSpan.tag("junction.response_mode", "json");

            var ctx = createRequestContext(traceId, resolvedApiKey.apiKey(), request.model(), httpServletRequest, requestSpan.trace());

            ApiKeyValidator.ValidationResult validation;
            try (var ignored = openLoggingContext(traceId, ctx)) {
                ScopedValue.where(RequestContext.key(), ctx).run(() -> {
                    logEmbeddingRequestDetails(request, resolvedApiKey, acceptHeader, userAgent, httpServletRequest, traceId);
                });

                validation = ScopedValue.where(RequestContext.key(), ctx).call(() -> {
                    return validateApiKey(resolvedApiKey.apiKey(), httpServletRequest, request.model());
                });
            }

            if (!validation.valid()) {
                recordAuthFailure(validation);
                tracker.finishFailure(validation.error().name());
                throw new ApiKeyAuthenticationException(validation);
            }

            EmbeddingResponse response = createEmbeddingResponse(request, traceId, ctx, tracker);
            tracker.finishSuccess();
            return ResponseEntity.ok()
                .header("X-Trace-ID", traceId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
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
    
    private void logRequestDetails(ChatCompletionRequest request, 
                                   ResolvedApiKey resolvedApiKey,
                                   String preferredProvider,
                                   String acceptHeader,
                                   String userAgent, HttpServletRequest httpServletRequest, String endpointType, java.util.UUID traceId) {
        log.info("[{}] === INCOMING REQUEST [{}] ===", traceId, endpointType);
        log.info("[{}] Client IP: {}", traceId, getClientIp(httpServletRequest));
        log.info("[{}] Request URI: {} {}", traceId, httpServletRequest.getMethod(), httpServletRequest.getRequestURI());
        log.info("[{}] Request Query String: {}", traceId, httpServletRequest.getQueryString());
        log.info("[{}] Request Model: {}", traceId, request.model());
        log.info("[{}] Request Provider Header: {}", traceId, preferredProvider != null ? preferredProvider : "<not-set>");
        log.info("[{}] Request Stream: {}", traceId, request.stream());
        log.info("[{}] Request Temperature: {}", traceId, request.temperature());
        log.info("[{}] Request Messages Count: {}", traceId, request.messages() != null ? request.messages().size() : "null");
        log.info("[{}] Accept Header: {}", traceId, acceptHeader);
        log.info("[{}] User-Agent Header: {}", traceId, userAgent);
        log.info("[{}] Authentication Source: {}", traceId, resolvedApiKey.source());
        log.info("[{}] === ===", traceId);

        logHeaderDetails(httpServletRequest);
    }

    private void logEmbeddingRequestDetails(EmbeddingRequest request, ResolvedApiKey resolvedApiKey, String acceptHeader,
                                            String userAgent, HttpServletRequest httpServletRequest, java.util.UUID traceId) {
        log.info("[{}] === INCOMING REQUEST [EMBEDDINGS] ===", traceId);
        log.info("[{}] Client IP: {}", traceId, getClientIp(httpServletRequest));
        log.info("[{}] Request URI: {} {}", traceId, httpServletRequest.getMethod(), httpServletRequest.getRequestURI());
        log.info("[{}] Request Query String: {}", traceId, httpServletRequest.getQueryString());
        log.info("[{}] Request Model: {}", traceId, request.model());
        log.info("[{}] Request Input Count: {}", traceId, request.input() != null ? request.input().size() : "null");
        log.info("[{}] Request Encoding Format: {}", traceId, request.encodingFormat());
        log.info("[{}] Request Dimensions: {}", traceId, request.dimensions());
        log.info("[{}] Request User Present: {}", traceId, request.user() != null && !request.user().isBlank());
        log.info("[{}] Accept Header: {}", traceId, acceptHeader);
        log.info("[{}] User-Agent Header: {}", traceId, userAgent);
        log.info("[{}] Authentication Source: {}", traceId, resolvedApiKey.source());
        log.info("[{}] === ===", traceId);

        logHeaderDetails(httpServletRequest);
    }

    private void logHeaderDetails(HttpServletRequest httpServletRequest) {
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
                                        RequestContext.Context ctx,
                                        String preferredProvider,
                                        JunctionObservabilityService.RequestTracker tracker,
                                        GatewayTracing.ContextSnapshot requestParentTraceContext) {
        var emitter = new SseEmitter(0L); 
        emitter.onCompletion(tracker::finishSuccess);
        emitter.onTimeout(() -> tracker.finishFailure("timeout"));
        emitter.onError(error -> tracker.finishFailure("stream_error"));

        ClientAdapterConfig adapterConfig;
        ChatCompletionRequest patchedRequest;
        ChatResponseAccumulator responseAccumulator;
        try (var ignored = openLoggingContext(traceId, ctx)) {
            log.info("[{}] Starting SSE stream for model: {}", traceId, request.model());

            
            adapterConfig = clientCompatService.detectClient(httpRequest);
            if (adapterConfig != null) {
                log.info("[{}] Detected client adapter: {}", traceId, adapterConfig.getId());
                tracker.clientAdapter(adapterConfig.getId());
            }
            patchedRequest = clientCompatService.applyRequestPatches(request, adapterConfig);
            responseAccumulator = isChatResponseLoggingEnabled()
                ? new ChatResponseAccumulator(patchedRequest.model())
                : null;
        }
        
        Thread.ofVirtual().start(() -> {
            try (var ignored = openLoggingContext(traceId, ctx);
                 var asyncSpan = gatewayTracing.startSpan("junction.gateway.stream", requestParentTraceContext)) {
                asyncSpan.tag("junction.endpoint", "chat");
                asyncSpan.tag("junction.response_mode", "sse");
                ScopedValue.where(RequestContext.key(), ctx).run(() -> {
                    try {
                        log.info("[{}] Routing request", traceId);
                        var provider = router.route(patchedRequest, preferredProvider);
                        log.info("[{}] Routed to provider: {}", traceId, provider.providerId());
                        tracker.provider(provider.providerId());
                        
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

                                    if (responseAccumulator != null) {
                                        responseAccumulator.append(chunk);
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
                                    tracker.finishFailure("stream_io_error");
                                    log.error("[{}] IOException sending chunk: {}", traceId, e.getMessage());
                                    emitter.completeWithError(e);
                                    return;
                                }
                            }
                        }

                        
                        log.info("[{}] Sending [DONE] marker", traceId);
                        emitter.send(SseEmitter.event()
                            .data("[DONE]"));

                        if (responseAccumulator != null) {
                            logChatResponseBody(traceId, responseAccumulator.toResponse());
                        }
                        
                        log.info("[{}] SSE stream completed successfully, sent {} chunks", traceId, chunkCount);
                        tracker.finishSuccess();
                        emitter.complete();
                        
                    } catch (ProviderException e) {
                        asyncSpan.error(e);
                        tracker.finishFailure("provider_error");
                        log.error("[{}] Provider error: {} (code: {})", traceId, e.getMessage(), e.getCode());
                        sendErrorEvent(emitter, e.getCode(), e.getMessage());
                        emitter.complete();
                    } catch (NoProviderAvailableException e) {
                        asyncSpan.error(e);
                        tracker.finishFailure("no_provider");
                        log.error("[{}] No provider available", traceId);
                        sendErrorEvent(emitter, 503, "No provider available");
                        emitter.complete();
                    } catch (Exception e) {
                        asyncSpan.error(e);
                        tracker.finishFailure("internal_error");
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
                                                      RequestContext.Context ctx,
                                                      String preferredProvider,
                                                      JunctionObservabilityService.RequestTracker tracker) {
        try (var ignored = openLoggingContext(traceId, ctx)) {
            return ScopedValue.where(RequestContext.key(), ctx).call(() -> {
                ClientAdapterConfig adapterConfig = clientCompatService.detectClient(httpRequest);
                if (adapterConfig != null) {
                    log.info("[{}] Detected client adapter: {}", traceId, adapterConfig.getId());
                    tracker.clientAdapter(adapterConfig.getId());
                }

                ChatCompletionRequest patchedRequest = clientCompatService.applyRequestPatches(request, adapterConfig);
                var provider = router.route(patchedRequest, preferredProvider);
                tracker.provider(provider.providerId());
                var gatherer = provider.responseAdapter();
                var responseAccumulator = new ChatResponseAccumulator(patchedRequest.model());
                
                try (var stream = provider.execute(patchedRequest).gather(gatherer)) {
                    stream.forEach(chunk -> {
                        if (adapterConfig != null) {
                            chunk = clientCompatService.applyResponsePatches(chunk, adapterConfig);
                        }
                        responseAccumulator.append(chunk);
                    });
                }

                ChatCompletionResponse response = responseAccumulator.toResponse();
                logChatResponseBody(traceId, response);
                log.debug("[{}] Returning JSON response", ctx.traceId());
                return response;
            });
        }
    }

    private boolean isChatResponseLoggingEnabled() {
        return junctionProperties.getLogging().getChatResponse().isEnabled() && responsePayloadLog.isDebugEnabled();
    }

    private void logChatResponseBody(java.util.UUID traceId, ChatCompletionResponse response) {
        if (!isChatResponseLoggingEnabled()) {
            return;
        }

        try {
            responsePayloadLog.debug("[{}] Chat response body: {}", traceId, jsonMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.warn("[{}] Failed to serialize chat response body for logging: {}", traceId, e.getMessage());
        }
    }

    private static final class ChatResponseAccumulator {
        private final String fallbackModel;
        private final StringBuilder contentBuilder = new StringBuilder();
        private final java.util.List<ChatCompletionChunk.ToolCall> toolCalls = new java.util.ArrayList<>();
        private String model;

        private ChatResponseAccumulator(String fallbackModel) {
            this.fallbackModel = fallbackModel;
        }

        private void append(ChatCompletionChunk chunk) {
            if (chunk == null) {
                return;
            }

            if (chunk.choices() != null && !chunk.choices().isEmpty()) {
                var choice = chunk.choices().get(0);
                if (choice.delta() != null) {
                    if (choice.delta().content() != null) {
                        contentBuilder.append(choice.delta().content());
                    }
                    if (choice.delta().toolCalls() != null) {
                        toolCalls.addAll(choice.delta().toolCalls());
                    }
                }
            }

            if (chunk.model() != null && !chunk.model().isBlank()) {
                model = chunk.model();
            }
        }

        private ChatCompletionResponse toResponse() {
            java.util.List<ChatCompletionResponse.ToolCall> responseToolCalls = null;
            if (!toolCalls.isEmpty()) {
                responseToolCalls = new java.util.ArrayList<>();
                for (var tc : toolCalls) {
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

            return ChatCompletionResponse.complete(
                model != null ? model : fallbackModel,
                contentBuilder.toString(),
                responseToolCalls
            );
        }
    }

    private String resolveRequestedProvider(String providerHeader) {
        if (providerHeader == null) {
            return null;
        }

        var normalized = providerHeader.trim().toLowerCase();
        return normalized.isBlank() ? null : normalized;
    }

    private EmbeddingResponse createEmbeddingResponse(EmbeddingRequest request,
                                                      java.util.UUID traceId,
                                                      RequestContext.Context ctx,
                                                      JunctionObservabilityService.RequestTracker tracker) {
        try (var ignored = openLoggingContext(traceId, ctx)) {
            return ScopedValue.where(RequestContext.key(), ctx).call(() -> {
                validateEmbeddingRequest(request);

                log.info("[{}] Routing embeddings request", traceId);
                var provider = router.route(request);
                log.info("[{}] Routed embeddings to provider: {}", traceId, provider.providerId());
                tracker.provider(provider.providerId());

                var response = provider.embed(request);
                log.debug("[{}] Returning embeddings response with {} vectors", traceId, response.data().size());
                return response;
            });
        }
    }

    private void validateEmbeddingRequest(EmbeddingRequest request) {
        if (request.model() == null || request.model().isBlank()) {
            throw new RouterException("Embeddings request requires a non-blank 'model'.");
        }

        if (request.input() == null || request.input().isEmpty()) {
            throw new RouterException(
                "Embeddings request requires 'input' as a string or a non-empty array of strings."
            );
        }

        if (request.encodingFormat() != null
            && !"float".equals(request.encodingFormat())
            && !"base64".equals(request.encodingFormat())) {
            throw new RouterException("Only 'float' and 'base64' encoding_format values are supported for embeddings.");
        }

        if (request.dimensions() != null) {
            throw new RouterException("'dimensions' is not supported for embeddings.");
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
            HttpServletRequest request,
            HttpServletResponse response) {
        var traceId = java.util.UUID.randomUUID();
        var requestParentTraceContext = gatewayTracing.fromIncomingHeaders(
            request.getHeader("traceparent"),
            request.getHeader("tracestate"),
            request.getHeader("baggage")
        );
        setTraceHeader(response, traceId);
        var tracker = registerRequestTracker(request, "models", "json");
        var resolvedApiKey = resolveApiKey(request);
        tracker.authSource(resolvedApiKey.source());
        
        try (var requestSpan = gatewayTracing.startSpan("junction.gateway.request", requestParentTraceContext)) {
            requestSpan.tag("junction.endpoint", "models");
            requestSpan.tag("junction.response_mode", "json");

            var ctx = createRequestContext(traceId, resolvedApiKey.apiKey(), null, request, requestSpan.trace());

            var clientIp = getClientIp(request);

            var validation = validateApiKey(resolvedApiKey.apiKey(), request, null);
            if (!validation.valid()) {
                recordAuthFailure(validation);
                tracker.finishFailure(validation.error().name());
                throw new ApiKeyAuthenticationException(validation);
            }

            var ipRateResult = ipRateLimiter.checkAndIncrement(clientIp);
            if (!ipRateResult.isAllowed()) {
                observabilityService.recordAuthFailure("ip_rate_limit_exceeded");
                tracker.finishFailure("ip_rate_limit_exceeded");
                log.warn("IP {} exceeded rate limit for /models endpoint", clientIp);
                throw new IpRateLimitExceededException(clientIp, ipRateResult);
            }

            var models = ScopedValue.where(RequestContext.key(), ctx).call(() -> {
                try (var ignored = openLoggingContext(traceId, ctx)) {
                    var collectedModels = new java.util.ArrayList<ModelInfo>();
                    tracker.provider("aggregate");

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
                        collectedModels.addAll(ollamaModels);
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
                        collectedModels.addAll(geminiModels);
                    }
                    return collectedModels;
                }
            });

            tracker.finishSuccess();
            return ResponseEntity.ok()
                .header("X-Trace-ID", traceId.toString())
                .body(ModelList.of(models));
        }
    }
    
    
    
    @ExceptionHandler(ApiKeyAuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleApiKeyAuthentication(ApiKeyAuthenticationException e,
                                                                    HttpServletRequest request) {
        var outcome = e.getErrorCode() != null ? e.getErrorCode() : "invalid_key";
        if (finishTrackedRequest(request, outcome)) {
            observabilityService.recordAuthFailure(outcome);
        }
        log.warn("API key authentication failed: {}", e.getMessage());
        return ResponseEntity.status(e.getHttpStatus())
                .body(new ErrorResponse(e.getMessage(), e.getErrorCode(), e.getHttpStatus()));
    }
    
    @ExceptionHandler(IpRateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleIpRateLimitExceeded(IpRateLimitExceededException e,
                                                                   HttpServletRequest request) {
        if (finishTrackedRequest(request, "ip_rate_limit_exceeded")) {
            observabilityService.recordAuthFailure("ip_rate_limit_exceeded");
        }
        log.warn("IP rate limit exceeded for {}: {}", e.getIpAddress(), e.getMessage());
        long retryAfter = e.getRetryAfter() - System.currentTimeMillis() / 1000;
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(Math.max(1, retryAfter)))
                .body(new ErrorResponse(e.getMessage(), "rate_limit_exceeded", 429));
    }
    
    @ExceptionHandler(ProviderException.class)
    public ResponseEntity<ErrorResponse> handleProviderException(ProviderException e,
                                                                 HttpServletRequest request) {
        finishTrackedRequest(request, "provider_error");
        log.error("ProviderException: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse(e.getMessage(), "provider_error", e.getCode()));
    }
    
    @ExceptionHandler(NoProviderAvailableException.class)
    public ResponseEntity<ErrorResponse> handleNoProviderAvailable(NoProviderAvailableException e,
                                                                   HttpServletRequest request) {
        finishTrackedRequest(request, "no_provider");
        log.error("NoProviderAvailableException: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("No provider available", "service_unavailable", 503));
    }
    
    @ExceptionHandler(RouterException.class)
    public ResponseEntity<ErrorResponse> handleRouterException(RouterException e,
                                                               HttpServletRequest request) {
        finishTrackedRequest(request, "invalid_request");
        log.error("RouterException: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(e.getMessage(), "invalid_request", 400));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
                                                                      HttpServletRequest request) {
        finishTrackedRequest(request, "invalid_request");
        String message = "Invalid request body.";
        Throwable cause = e.getMostSpecificCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            message = cause.getMessage();
        }

        log.warn("Invalid request body: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(message, "invalid_request", 400));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException e,
                                                                        HttpServletRequest request) {
        finishTrackedRequest(request, "invalid_request");
        String message = e.getMessage() == null || e.getMessage().isBlank()
            ? "Invalid request."
            : e.getMessage();

        log.warn("Invalid request argument: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(message, "invalid_request", 400));
    }
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception e,
                                                               HttpServletRequest request) {
        finishTrackedRequest(request, "internal_error");
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

    private JunctionObservabilityService.RequestTracker registerRequestTracker(HttpServletRequest request,
                                                                               String endpoint,
                                                                               String responseMode) {
        var tracker = observabilityService.startRequest(endpoint, responseMode);
        request.setAttribute(REQUEST_TRACKER_ATTRIBUTE, tracker);
        return tracker;
    }

    private boolean finishTrackedRequest(HttpServletRequest request, String outcome) {
        if (request == null) {
            return false;
        }

        Object attribute = request.getAttribute(REQUEST_TRACKER_ATTRIBUTE);
        if (!(attribute instanceof JunctionObservabilityService.RequestTracker tracker)) {
            return false;
        }
        if (tracker.isFinished()) {
            return false;
        }

        tracker.finishFailure(outcome);
        return true;
    }

    private void recordAuthFailure(ApiKeyValidator.ValidationResult validation) {
        if (validation.error() != null) {
            observabilityService.recordAuthFailure(validation.error().name());
        }
    }

    private void setTraceHeader(HttpServletResponse response, java.util.UUID traceId) {
        response.setHeader("X-Trace-ID", traceId.toString());
    }

    private RequestContext.Context createRequestContext(java.util.UUID traceId,
                                                        String apiKey,
                                                        String model,
                                                        HttpServletRequest request,
                                                        RequestContext.DistributedTrace distributedTrace) {
        return new RequestContext.Context(
            traceId,
            apiKey,
            model,
            Instant.now(),
            new RequestContext.DistributedTrace(
                distributedTrace.traceId(),
                distributedTrace.spanId(),
                request != null ? request.getHeader("tracestate") : null,
                request != null ? request.getHeader("baggage") : null
            )
        );
    }

    private ManagedCloseable openLoggingContext(java.util.UUID traceId, RequestContext.Context ctx) {
        return new CompositeCloseable(
            MDC.putCloseable("traceId", traceId.toString())::close,
            optionalMdc("otelTraceId", ctx != null ? ctx.distributedTraceId() : null),
            optionalMdc("otelSpanId", ctx != null ? ctx.distributedSpanId() : null)
        );
    }

    private ManagedCloseable optionalMdc(String key, String value) {
        if (value == null || value.isBlank()) {
            return () -> { };
        }
        var closeable = MDC.putCloseable(key, value);
        return closeable::close;
    }

    private interface ManagedCloseable extends AutoCloseable {
        @Override
        void close();
    }

    private record CompositeCloseable(ManagedCloseable... closeables) implements ManagedCloseable {
        @Override
        public void close() {
            RuntimeException firstFailure = null;
            for (int i = closeables.length - 1; i >= 0; i--) {
                try {
                    closeables[i].close();
                } catch (RuntimeException ex) {
                    if (firstFailure == null) {
                        firstFailure = ex;
                    }
                }
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
        }
    }

    private record ResolvedApiKey(String apiKey, String source) {}
    
}
