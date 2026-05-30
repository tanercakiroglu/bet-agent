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
 *  jakarta.persistence.UniqueConstraint
 */
package com.betagent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

@Entity
@Table(name="provider_events", uniqueConstraints={@UniqueConstraint(columnNames={"provider", "provider_match_id"})})
public class ProviderEventEntity {
    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    public Long id;
    @Column(nullable=false, length=64)
    public String provider;
    @Column(name="provider_match_id", nullable=false, length=64)
    public String providerMatchId;
    public String sport;
    @Column(name="league_name")
    public String leagueName;
    @Column(name="league_slug")
    public String leagueSlug;
    @Column(name="competition_code")
    public String competitionCode;
    @Column(name="event_date")
    public LocalDateTime eventDate;
    @Column(name="home_team", nullable=false)
    public String homeTeam;
    @Column(name="away_team", nullable=false)
    public String awayTeam;
    public String status;
    @Column(name="scores_json", columnDefinition="text")
    public String scoresJson;
    @Column(name="raw_json", columnDefinition="text")
    public String rawJson;
    @Column(name="first_seen_at", nullable=false)
    public LocalDateTime firstSeenAt;
    @Column(name="last_seen_at", nullable=false)
    public LocalDateTime lastSeenAt;
}

