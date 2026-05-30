/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.persistence.Column
 *  jakarta.persistence.Embeddable
 */
package com.betagent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ProviderMatchId
implements Serializable {
    public String provider;
    @Column(name="provider_match_id")
    public String providerMatchId;

    public ProviderMatchId() {
    }

    public ProviderMatchId(String provider, String providerMatchId) {
        this.provider = provider;
        this.providerMatchId = providerMatchId;
    }

    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProviderMatchId)) {
            return false;
        }
        ProviderMatchId that = (ProviderMatchId)o;
        return Objects.equals(this.provider, that.provider) && Objects.equals(this.providerMatchId, that.providerMatchId);
    }

    public int hashCode() {
        return Objects.hash(this.provider, this.providerMatchId);
    }
}

