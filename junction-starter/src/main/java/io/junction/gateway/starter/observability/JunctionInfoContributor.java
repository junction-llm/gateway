package io.junction.gateway.starter.observability;

import io.junction.gateway.core.cache.ModelCacheService;
import io.junction.gateway.core.provider.LlmProvider;
import io.junction.gateway.core.router.Router;
import io.junction.gateway.starter.JunctionProperties;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

import java.util.LinkedHashMap;

public class JunctionInfoContributor implements InfoContributor {
    private final Router router;
    private final JunctionProperties properties;
    private final ModelCacheService modelCacheService;

    public JunctionInfoContributor(Router router, JunctionProperties properties, ModelCacheService modelCacheService) {
        this.router = router;
        this.properties = properties;
        this.modelCacheService = modelCacheService;
    }

    @Override
    public void contribute(Info.Builder builder) {
        var junction = new LinkedHashMap<String, Object>();
        junction.put("enabledProviders", router.getProviders().stream().map(LlmProvider::providerId).sorted().toList());
        junction.put("apiKeyRequired", properties.getApiKeyConfig().isRequired());
        junction.put("apiKeyStorage", properties.getApiKeyConfig().getStorage());
        junction.put("clientAdapterHotReload", properties.getClientAdapters().isHotReload());
        junction.put("chatResponseLoggingEnabled", properties.getLogging().getChatResponse().isEnabled());
        junction.put("cacheWriteEnabled", properties.getObservability().getAdmin().isCacheWriteEnabled());
        junction.put("modelCacheTtlSeconds", modelCacheService.getCacheTtl().toSeconds());
        builder.withDetail("junction", junction);
    }
}
