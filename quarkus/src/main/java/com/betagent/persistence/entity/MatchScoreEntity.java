/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.persistence.Column
 *  jakarta.persistence.Entity
 *  jakarta.persistence.Id
 *  jakarta.persistence.IdClass
 *  jakarta.persistence.Table
 */
package com.betagent.persistence.entity;

import com.betagent.persistence.entity.ProviderMatchId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name="match_scores")
@IdClass(value=ProviderMatchId.class)
public class MatchScoreEntity {
    @Id
    @Column(nullable=false, length=64)
    public String provider;
    @Id
    @Column(name="provider_match_id", nullable=false, length=64)
    public String providerMatchId;
    public int hthg;
    public int htag;
    public int fthg;
    public int ftag;
    @Column(name="ht_result", nullable=false, length=1)
    public String htResult;
    @Column(name="ft_result", nullable=false, length=1)
    public String ftResult;
    @Column(name="htft_code", nullable=false, length=8)
    public String htftCode;
    @Column(name="first_half_kg", nullable=false, length=8)
    public String firstHalfKg;
    @Column(name="first_half_kg_taraf_code", nullable=false, length=32)
    public String firstHalfKgTarafCode;
}

