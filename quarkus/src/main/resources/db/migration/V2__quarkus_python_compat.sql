-- Align legacy Python schema with Quarkus JPA entities

do $$
begin
    if exists (
        select 1 from information_schema.columns
        where table_schema = 'public' and table_name = 'odds_snapshots' and column_name = 'odds_snapshot_id'
    ) then
        alter table odds_snapshots rename column odds_snapshot_id to id;
    end if;
end $$;

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
