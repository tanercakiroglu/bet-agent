# Bet Agent

Football odds archive and sharp prediction dashboard.

- **Backend:** Quarkus (Java 21) — `quarkus/`
- **Frontend:** React + Vite — `frontend/`
- **Providers:** Odds-API.io (ilk yari karsilikli gol + taraf) + Nesine (HT/FT)

## Quick start (Docker)

Requirements: [Docker Desktop](https://www.docker.com/products/docker-desktop/) (or Docker Engine + Compose v2).

```bash
git clone https://github.com/tanercakiroglu/bet-agent.git
cd bet-agent
cp .env.example .env    # Windows: copy .env.example .env
# Edit .env and set ODDS_API_IO_KEY=your_key
docker compose up --build
```

Open **http://localhost:5173** (dashboard). API: **http://localhost:8080/api**.

First startup builds images and runs DB migrations (~2–5 min). Nesine bulten works without an API key; Odds-API.io collection needs `ODDS_API_IO_KEY` in `.env`.

### Useful commands

```bash
docker compose up -d --build   # detached
docker compose logs -f backend
docker compose down
docker compose down -v           # also removes postgres volume
```

## Project layout

| Path | Description |
|------|-------------|
| `docker-compose.yml` | Postgres + backend + frontend |
| `quarkus/Dockerfile` | Quarkus backend image |
| `frontend/Dockerfile` | Vite dev server (port 5173) |
| `.env.example` | Environment template (copy to `.env`) |

## Markets

- **Odds-API.io:** `FIRST_HALF_KG_TARAF` = ilk yari BTTS (Var) × ilk yari 1X2 (1/X/2)
- **Nesine:** HT/FT — `1/2`, `2/1`, `1/X`, `2/X`

## Local development (without Docker)

**Backend**

```bash
cd quarkus
mvn quarkus:dev
```

**Frontend**

```bash
cd frontend
npm install
npm run dev
```

Set `VITE_API_BASE_URL=http://localhost:8080/api` if needed.
