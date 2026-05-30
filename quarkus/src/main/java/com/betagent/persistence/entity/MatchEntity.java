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
import java.time.LocalDate;

@Entity
@Table(name="matches")
@IdClass(value=ProviderMatchId.class)
public class MatchEntity {
    @Id
    @Column(nullable=false, length=64)
    public String provider;
    @Id
    @Column(name="provider_match_id", nullable=false, length=64)
    public String providerMatchId;
    @Column(name="competition_code")
    public String competitionCode;
    @Column(nullable=false, length=16)
    public String season;
    @Column(name="match_date")
    public LocalDate matchDate;
    @Column(name="home_team", nullable=false)
    public String homeTeam;
    @Column(name="away_team", nullable=false)
    public String awayTeam;
}

