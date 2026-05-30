/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  jakarta.persistence.Column
 *  jakarta.persistence.Entity
 *  jakarta.persistence.Id
 *  jakarta.persistence.Table
 */
package com.betagent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name="prediction_settings")
public class PredictionSettingsEntity {
    @Id
    @Column(nullable=false, length=64)
    public String provider;
    @Column(name="min_samples", nullable=false)
    public int minSamples;
    @Column(name="min_edge", nullable=false)
    public double minEdge;
    @Column(name="min_confidence_low", nullable=false)
    public double minConfidenceLow;
    @Column(name="updated_at", nullable=false)
    public LocalDateTime updatedAt;
}

