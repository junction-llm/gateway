package io.junction.gateway.starter.clientcompat;

import io.junction.gateway.core.model.ChatCompletionChunk;
import io.junction.gateway.core.model.ChatCompletionRequest;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Interface for client adapters that apply compatibility patches
 * based on detected client characteristics.
 * 
 * @author Juan Hidalgo
 * @since 0.0.1
 */
public interface ClientAdapter {
    
    /**
     * Get the unique identifier for this adapter
     */
    String getId();
    
    /**
     * Get the display name for this adapter
     */
    String getName();
    
    /**
     * Get the configuration for this adapter
     */
    ClientAdapterConfig getConfig();
    
    /**
     * Check if this adapter matches the incoming request
     * 
     * @param request the HTTP servlet request
     * @return true if this adapter should be applied
     */
    boolean matches(HttpServletRequest request);
    
    /**
     * Apply request patches to modify the chat completion request
     * 
     * @param request the original request
     * @return the patched request
     */
    ChatCompletionRequest applyRequestPatches(ChatCompletionRequest request);
    
    /**
     * Apply response patches to modify the chat completion chunk
     * 
     * @param chunk the original response chunk
     * @return the patched chunk
     */
    ChatCompletionChunk applyResponsePatches(ChatCompletionChunk chunk);
}