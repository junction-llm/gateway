package io.junction.gateway.core.router;

import io.junction.gateway.core.model.ChatCompletionRequest;
import io.junction.gateway.core.provider.LlmProvider;
import java.util.List;

public interface Router {
    LlmProvider route(ChatCompletionRequest request);
}