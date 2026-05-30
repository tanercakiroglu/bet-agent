/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.quarkus.runtime.Quarkus
 *  io.quarkus.runtime.annotations.QuarkusMain
 */
package com.betagent;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class BetAgentApplication {
    public static void main(String[] args) {
        Quarkus.run((String[])args);
    }
}

