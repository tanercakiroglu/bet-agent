create table if not exists matches (
    id bigserial primary key,
    provider varchar(64) not null,
    provider_match_id varchar(64) not null,
    competition_code varchar(255) not null,
    season varchar(16) not null,
    match_date date,
    home_team varchar(255) not null,
    away_team varchar(255) not null,
    unique (provider, provider_match_id)
);

create table if not exists match_scores (
    id bigserial primary key,
    provider varchar(64) not null,
    provider_match_id varchar(64) not null,
    hthg integer not null,
    htag integer not null,
    fthg integer not null,
    ftag integer not null,
    ht_result varchar(1) not null,
    ft_result varchar(1) not null,
    htft_code varchar(8) not null,
    first_half_kg varchar(8) not null,
    first_half_kg_taraf_code varchar(32) not null,
    unique (provider, provider_match_id)
);

create table if not exists odds_snapshots (
    id bigserial primary key,
    provider varchar(64) not null,
    provider_match_id varchar(64) not null,
    bookmaker varchar(64) not null,
    market varchar(64) not null,
    outcome varchar(32) not null,
    decimal_odds numeric(10, 4) not null,
    snapshot_type varchar(32) not null,
    snapshot_at timestamp,
  unique (provider, provider_match_id, bookmaker, market, outcome, snapshot_type, snapshot_at)
);

create table if not exists provider_events (
    id bigserial primary key,
    provider varchar(64) not null,
    provider_match_id varchar(64) not null,
    sport varchar(64),
    league_name varchar(255),
    league_slug varchar(255),
    competition_code varchar(255),
    event_date timestamp,
    home_team varchar(255) not null,
    away_team varchar(255) not null,
    status varchar(32),
    scores_json text,
    raw_json text,
    first_seen_at timestamp not null,
    last_seen_at timestamp not null,
    unique (provider, provider_match_id)
);

create table if not exists provider_sync_runs (
    id uuid primary key,
    provider varchar(64) not null,
    status varchar(32) not null,
    started_at timestamp not null,
    finished_at timestamp,
    request_budget integer not null,
    request_count integer not null default 0,
    events_seen integer not null default 0,
    settled_events integer not null default 0,
    pending_events integer not null default 0,
    odds_payloads integer not null default 0,
    odds_snapshots_inserted integer not null default 0,
    matches_inserted integer not null default 0,
    future_fixtures integer not null default 0,
    failures_json text
);

create table if not exists market_results (
    id bigserial primary key,
    provider varchar(64) not null,
    provider_match_id varchar(64) not null,
    market varchar(64) not null,
    outcome varchar(32) not null,
    won boolean not null,
    unique (provider, provider_match_id, market, outcome)
);
