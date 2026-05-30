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

@ConfigMapping(prefix="betagent.predictions")
public interface PredictionConfig {
    @WithDefault(value="8")
    public int minSamples();

    @WithDefault(value="0.08")
    public double minEdge();

    @WithDefault(value="0.45")
    public double minConfidenceLow();

    @WithDefault(value="true")
    public boolean wilsonScaleByImplied();
}

