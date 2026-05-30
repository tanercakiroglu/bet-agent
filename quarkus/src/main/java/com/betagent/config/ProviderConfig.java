/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.smallrye.config.ConfigMapping
 *  io.smallrye.config.WithDefault
 */
package com.betagent.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix="betagent.provider")
public interface ProviderConfig {
    @WithDefault(value="odds-api-io")
    public String active();

    @WithDefault(value="odds-api-io,nesine")
    public String enabled();
}

