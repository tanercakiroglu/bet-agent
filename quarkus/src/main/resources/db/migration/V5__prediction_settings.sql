create table if not exists prediction_settings (
    provider varchar(64) primary key,
    min_samples integer not null,
    min_edge double precision not null,
    min_confidence_low double precision not null,
    updated_at timestamp not null default now()
);
