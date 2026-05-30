alter table provider_sync_runs
    add column if not exists optimization_stats_json text;
