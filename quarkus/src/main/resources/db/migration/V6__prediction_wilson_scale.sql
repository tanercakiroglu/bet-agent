alter table prediction_settings
    add column if not exists wilson_scale_by_implied boolean not null default true;
