alter table provider_sync_runs
    add column if not exists league_stats_json text;
