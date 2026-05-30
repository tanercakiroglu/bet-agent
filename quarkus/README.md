# Bet Agent (Quarkus)

Reactive odds archive and sharp predictions. External feeds are pluggable via `OddsDataProvider`.

## Configuration (.env)

```env
ODDS_API_IO_KEY=...
ODDS_API_IO_BASE_URL=https://api.odds-api.io/v3
ODDS_API_IO_SPORT=football
ODDS_API_IO_BOOKMAKERS=Bet365
BETAGENT_ODDS_PROVIDER=odds-api-io
```

## Provider pattern

| Piece | Role |
|-------|------|
| `OddsDataProvider` | Port — events + odds fetch |
| `OddsApiIoProvider` | Odds-API.io implementation |
| `OddsApiIoRestClient` | MicroProfile REST client (`@RegisterRestClient`) |
| `OddsProviderRegistry` | Resolves `betagent.provider.active` |

To add another bookmaker API tomorrow:

1. Create `provider/foo/client/FooRestClient.java` (`@RegisterRestClient(configKey = "foo")`)
2. Implement `OddsDataProvider` in `FooProvider`
3. Register REST URL in `application.properties`
4. Set `BETAGENT_ODDS_PROVIDER=foo`

Collector, warehouse, and REST API stay unchanged.

## Database

PostgreSQL database name: **`betting_agent`** (user/password: `betting_agent`).

DBeaver: `localhost:5432` -> database `betting_agent`

## End-to-end data flow

1. Source API (`Odds-API.io`)
   - `/events` returns fixture metadata (`pending` + `settled`), league, teams, date.
   - `/odds` returns market/outcome/decimal odds (currently Bet365).

2. Collector scheduling
   - Hourly cron run: `0 0 * ? * *`.
   - Startup run: one immediate collection on app boot.
   - Failed-run retry: every `15m` (`collection-tick-interval`).
   - Manual trigger: `POST /api/collector/run?force=true`.

3. Ingestion and persistence
   - `provider_events`: raw provider event payload and normalized event fields.
   - `matches`: match metadata.
   - `match_scores`: HT/FT scores and derived codes (`htft_code`, `first_half_kg_taraf_code`).
   - `odds_snapshots`: archived odds snapshots (`hourly_pending` / `hourly_settled`).
   - `provider_sync_runs`: job history with request usage, optimization stats, and league stats.

4. No-odds-loss bridge when match settles
   - If settled odds are unavailable from provider later, the system bridges latest `hourly_pending` odds
     into `hourly_settled` (`bridgePendingOddsToSettled`).
   - This keeps historical odds usable for training and prediction joins.

5. Prediction pipeline (`SharpPredictionService`)
   - Future candidates come from latest `hourly_pending` odds on non-settled events.
   - Historical samples come from scored matches + archived odds snapshots.
   - Supported candidate markets:
     - `HTFT`: `1/2`, `2/1`, `1/X`, `2/X`
     - `FIRST_HALF_1X2`: `1`, `X`, `2`
     - `FIRST_HALF_KG_TARAF`: `KG_VAR_1`, `KG_VAR_X`, `KG_VAR_2`
   - Synthetic generation:
     - `FIRST_HALF_BTTS(VAR)` x `FIRST_HALF_1X2(side)` -> synthetic `FIRST_HALF_KG_TARAF(KG_VAR_side)`
       for both future candidates and history when direct market is missing.
   - HTFT (`1/2`, `2/1`, `1/X`, `2/X`) must come from the bookmaker API (e.g. SBOBET `Half Time/Full Time` market).
     Bet365 via Odds-API.io does not expose HT/FT; configure `ODDS_API_IO_BOOKMAKERS=Bet365,SBOBET`.

6. Scoring and filtering
   - `implied = 1 / odds`
   - `edge = hitRate - implied`
   - confidence uses Wilson lower bound.
   - default thresholds:
     - `min_samples = 8`
     - `min_edge = 0.08`
     - `min_confidence_low = 0.45`
   - output is ranked by composite score and returned from `GET /api/predictions`.

7. API endpoints used by frontend
   - `GET /api/dashboard`
   - `GET /api/collector/status`
   - `GET /api/collector/history`
   - `POST /api/collector/run?force=true`
   - `GET /api/predictions`
   - `GET /api/scores`
   - `POST /api/database/reset`

## Run

```bash
docker compose up -d --build
```

- API: http://localhost:8080/api/health
- UI: http://localhost:5173
