/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.persistence.Column
 *  jakarta.persistence.Entity
 *  jakarta.persistence.GeneratedValue
 *  jakarta.persistence.GenerationType
 *  jakarta.persistence.Id
 *  jakarta.persistence.Table
 */
package com.betagent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name="odds_snapshots")
public class OddsSnapshotEntity {
    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    public Long id;
    @Column(nullable=false, length=64)
    public String provider;
    @Column(name="provider_match_id", nullable=false, length=64)
    public String providerMatchId;
    @Column(nullable=false, length=64)
    public String bookmaker;
    @Column(nullable=false, length=64)
    public String market;
    @Column(nullable=false, length=32)
    public String outcome;
    @Column(name="decimal_odds", nullable=false, precision=10, scale=4)
    public BigDecimal decimalOdds;
    @Column(name="snapshot_type", nullable=false, length=32)
    public String snapshotType;
    @Column(name="snapshot_at")
    public LocalDateTime snapshotAt;
}

