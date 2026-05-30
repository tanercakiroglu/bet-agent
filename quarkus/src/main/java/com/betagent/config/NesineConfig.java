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
import java.util.Optional;

@ConfigMapping(prefix="betagent.nesine")
public interface NesineConfig {
    @WithDefault(value="https://bulten.nesine.com")
    public String baseUrl();

    @WithDefault(value="https://ls.nesine.com")
    public String liveScoreUrl();

    @WithDefault(value="true")
    public boolean enabled();

    Optional<String> username();

    Optional<String> password();

    @WithDefault(value="true")
    public boolean scoreJobEnabled();

    @WithDefault(value="10m")
    public String scoreJobInterval();

    @WithDefault(value="true")
    public boolean scoreBackfillOnStartup();
}

