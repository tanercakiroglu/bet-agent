/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.enterprise.context.ApplicationScoped
 *  jakarta.enterprise.inject.Instance
 *  jakarta.inject.Inject
 */
package com.betagent.provider;

import com.betagent.config.NesineConfig;
import com.betagent.config.ProviderConfig;
import com.betagent.provider.OddsDataProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class OddsProviderRegistry {
    private final ProviderConfig config;
    private final NesineConfig nesineConfig;
    private final Map<String, OddsDataProvider> providersById;

    @Inject
    public OddsProviderRegistry(ProviderConfig config, NesineConfig nesineConfig, Instance<OddsDataProvider> providers) {
        this.config = config;
        this.nesineConfig = nesineConfig;
        this.providersById = providers.stream().collect(Collectors.toUnmodifiableMap(OddsDataProvider::id, p -> p));
    }

    public OddsDataProvider active() {
        OddsDataProvider provider = this.providersById.get(this.config.active());
        if (provider == null) {
            throw new IllegalStateException("Unknown odds provider '%s'. Registered: %s".formatted(this.config.active(), this.providersById.keySet()));
        }
        return provider;
    }

    public String activeId() {
        return this.config.active();
    }

    public List<OddsDataProvider> configuredProviders() {
        ArrayList<OddsDataProvider> out = new ArrayList<OddsDataProvider>();
        for (String id : this.enabledIds()) {
            OddsDataProvider provider = this.providersById.get(id);
            if (provider == null || "nesine".equals(id) && !this.nesineConfig.enabled() || !provider.configured()) continue;
            out.add(provider);
        }
        return List.copyOf(out);
    }

    public List<String> configuredCatalogNames() {
        return this.configuredProviders().stream().map(OddsDataProvider::catalogName).toList();
    }

    public List<String> enabledIds() {
        return Arrays.stream(this.config.enabled().split(",")).map(String::trim).filter(part -> !part.isEmpty()).toList();
    }
}

