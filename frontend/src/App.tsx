import { useCallback, useEffect, useRef, useState } from "react";

const apiBase = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api";

function marketQualityLabel(market: string): string {
  switch (market) {
    case "HTFT":
      return "HT/FT (1/2, 2/1...)";
    case "FIRST_HALF_1X2":
      return "IY 1X2";
    case "FIRST_HALF_KG_TARAF":
      return "Ilk Yari Karsilikli Gol + Taraf";
    case "FIRST_HALF_BTTS":
      return "Ilk Yari Karsilikli Gol";
    default:
      return market;
  }
}

type DataQuality = {
  impossible_ft_lt_ht: number;
  settled_missing_ht_breakdown: number;
  scored_matches: number;
  htft_distinct_pending_matches?: number;
  htft_settled_matches?: number;
  history_by_market: {
    market: string;
    pending_matches_with_odds: number;
    scored_matches_with_odds: number;
  }[];
  htft_pending_outcomes?: {
    outcome: string;
    pending_matches: number;
    settled_matches?: number;
  }[];
  htft_settled_outcomes?: {
    outcome: string;
    pending_matches?: number;
    settled_matches: number;
  }[];
  bands_with_min_samples_count: number;
  bands_with_min_samples: { market: string; outcome: string; band: string; sample_count: number }[];
  by_provider?: (DataQuality & { provider: string })[];
};

type Dashboard = {
  matches: number;
  hourly_snapshots: number;
  odds_snapshots: number;
  collector_running: boolean;
  data_quality?: DataQuality;
  prediction_settings?: PredictionSettings;
  provider: {
    configured: boolean;
    enabled_providers?: string[];
    request_budget: number;
    request_budget_per_key?: number;
    api_key_count?: number;
    usable_api_keys?: number;
    max_odds_requests_per_run?: number;
    odds_fetch_mode?: string;
    bookmakers?: string;
    league_keywords: number;
    nesine_enabled?: boolean;
  };
  providers?: {
    id: string;
    name: string;
    configured: boolean;
    source?: string;
    markets?: string;
    bookmakers?: string;
  }[];
  collector: {
    status: string;
    settled_events?: number;
    pending_events?: number;
    odds_snapshots_inserted?: number;
    matches_inserted?: number;
    request_count?: number;
    request_budget?: number;
  } | null;
};

type PredictionSettings = {
  min_samples: number;
  min_edge: number;
  min_confidence_low: number;
  updated_at?: string;
};

type LeagueStat = {
  league: string;
  settled: number;
  pending: number;
  total?: number;
};

type JobHistoryItem = {
  run_id: string;
  provider?: string;
  status: string;
  started_at: string;
  finished_at: string | null;
  request_count: number;
  request_budget: number;
  events_seen: number;
  settled_events: number;
  pending_events: number;
  matches_inserted: number;
  odds_snapshots_inserted: number;
  failure_message?: string | null;
  leagues: LeagueStat[];
  optimization?: {
    job_type?: string;
    trigger?: string;
    from_live_score?: number;
    from_cross_provider?: number;
    bridged_odds?: number;
    settled_this_run?: number;
    tracked_total?: number;
    still_missing?: number;
    odds_fetch_mode?: string;
    odds_candidates?: number;
    odds_skipped_recent?: number;
    odds_skipped_budget?: number;
    odds_events_fetched?: number;
    max_odds_requests_per_run?: number;
  };
};

function isScoreJob(job: JobHistoryItem) {
  return job.optimization?.job_type === "nesine_score";
}

function scoreJobSummary(job: JobHistoryItem) {
  const opt = job.optimization;
  if (!opt) return "";
  const parts = [
    opt.from_live_score ? `${opt.from_live_score} LiveScore` : null,
    opt.from_cross_provider ? `${opt.from_cross_provider} cross` : null,
    opt.bridged_odds ? `${opt.bridged_odds} odds bridge` : null,
    opt.still_missing != null ? `${opt.still_missing} eksik` : null,
  ].filter(Boolean);
  return parts.length > 0 ? parts.join(" · ") : "Yeni skor yok";
}

type Prediction = {
  odds_provider?: string;
  odds_provider_id?: string;
  provider_match_id: string;
  competition_code: string;
  match_date: string;
  home_team: string;
  away_team: string;
  bookmaker: string;
  market: string;
  outcome: string;
  decimal_odds: number;
  odds_band?: string;
  sample_count?: number;
  hit_count?: number;
  hit_rate?: number;
  historical_roi?: number;
  implied_probability?: number;
  edge?: number;
  confidence_low?: number;
  confidence_tier?: string;
  score?: number;
  play_pick?: boolean;
  scope?: string;
  rationale?: string;
};

type ScoreRow = {
  provider_match_id: string;
  match_date: string;
  competition_code: string;
  home_team: string;
  away_team: string;
  hthg: number;
  htag: number;
  fthg: number;
  ftag: number;
  ht_result: string;
  ft_result: string;
  htft_code: string;
  first_half_kg: string;
  first_half_kg_taraf_code: string;
};

type HtftMatchRow = {
  provider: string;
  provider_match_id: string;
  bookmaker: string;
  competition_code: string;
  match_date: string;
  kickoff_at?: string;
  home_team: string;
  away_team: string;
  odds: Partial<Record<"1/2" | "2/1" | "1/X" | "2/X", number>>;
  snapshot_at: string;
  status: "pending" | "finished";
  hthg?: number;
  htag?: number;
  fthg?: number;
  ftag?: number;
  htft_code?: string;
};

type HtftOddsPage = {
  items: HtftMatchRow[];
  total: number;
  page: number;
  page_size: number;
  total_pages: number;
  bookmaker?: string;
};

type BootstrapPayload = {
  dashboard: Dashboard;
  predictions?: Prediction[];
  history?: JobHistoryItem[];
  scores?: ScoreRow[];
};

const HTFT_OUTCOMES = ["1/2", "2/1", "1/X", "2/X"] as const;
const HTFT_PAGE_SIZE = 20;
const HTFT_BOOKMAKERS = ["Nesine"];
const HTFT_SCORES_ONLY = "Skor";
const HTFT_SECTIONS = [...HTFT_BOOKMAKERS, HTFT_SCORES_ONLY] as const;

const PENDING_ODDS_SECTIONS = [
  {
    id: "nesine-htft",
    title: "Nesine · HT/FT (1/2 · 2/1 · 1/X · 2/X)",
    match: (p: Prediction) => p.market === "HTFT",
  },
  {
    id: "oddsapi-kg",
    title: "Odds-API.io · Ilk Yari Karsilikli Gol + Taraf",
    match: (p: Prediction) => p.market === "FIRST_HALF_KG_TARAF",
  },
] as const;

function sortPredictionsByOdds(items: Prediction[]) {
  return [...items].sort((a, b) => b.decimal_odds - a.decimal_odds);
}

type Tab = "predictions" | "history" | "scores" | "htft";

function formatOdd(value?: number) {
  return value != null && value > 0 ? value.toFixed(2) : "—";
}

function formatHtftScore(row: HtftMatchRow) {
  if (row.status !== "finished" || row.hthg == null || row.htag == null || row.fthg == null || row.ftag == null) {
    return "—";
  }
  return `${row.hthg}-${row.htag} / ${row.fthg}-${row.ftag}`;
}

function formatApiError(body: string, status: number) {
  try {
    const parsed = JSON.parse(body) as { details?: string; message?: string };
    if (parsed.details?.includes("BlockingOperationNotAllowed")) {
      return "Backend veritabani thread hatasi — docker compose up -d --build backend ile yeniden baslat.";
    }
    return parsed.details ?? parsed.message ?? body;
  } catch {
    return body || `HTTP ${status}`;
  }
}

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBase}${path}`, init);
  if (!response.ok) {
    throw new Error(formatApiError(await response.text(), response.status));
  }
  return response.json() as Promise<T>;
}

function pct(v: number) {
  return `${(v * 100).toFixed(1)}%`;
}

function isPlayPick(p: Prediction, minSamples = 8, minEdge = 0.08) {
  if (p.play_pick != null) {
    return p.play_pick;
  }
  const sampleFloor = Math.max(8, minSamples);
  const edgeFloor = Math.max(0.08, minEdge);
  return (p.sample_count ?? 0) >= sampleFloor && (p.edge ?? 0) >= edgeFloor && (p.historical_roi ?? 0) > 0;
}

function marketLabel(market: string, outcome: string) {
  if (market === "HTFT") return `IY/MS ${outcome.replace("/", " → ")}`;
  if (market === "FIRST_HALF_1X2") return `Ilk Yari Sonucu ${outcome}`;
  if (market === "FIRST_HALF_KG_TARAF") {
    const side = outcome.replace("KG_VAR_", "");
    return `Ilk Yari Karsilikli Gol (Var) + Ilk Yari Taraf ${side}`;
  }
  return `${market} ${outcome}`;
}

function formatKickoff(row: HtftMatchRow) {
  const value = row.kickoff_at ?? row.match_date;
  if (!value) return "—";
  const normalized = String(value).replace("T", " ");
  if (normalized.length >= 16) {
    return normalized.slice(0, 16);
  }
  if (normalized.length >= 10) {
    return normalized.slice(0, 10) + " 00:00";
  }
  return normalized;
}

function formatDt(value: string | null) {
  if (!value) return "-";
  return value.replace("T", " ").slice(0, 16);
}

function leagueTotal(league: LeagueStat) {
  return league.total ?? league.settled + league.pending;
}

function formatFailureMessage(message?: string | null) {
  if (!message) return null;
  const lower = message.toLowerCase();
  if (lower.includes("forced-stop")) {
    return "Manuel mudahale kaydi";
  }
  return message;
}

function regionOfCompetition(competitionCode: string) {
  const country = competitionCode.split(" - ")[0].toLowerCase();
  if (
    [
      "england",
      "spain",
      "italy",
      "germany",
      "france",
      "turkiye",
      "turkey",
      "sweden",
      "norway",
      "finland",
      "denmark",
      "iceland",
      "ireland",
      "scotland",
      "netherlands",
      "portugal",
      "belgium",
      "switzerland",
      "austria",
      "poland",
      "czechia",
      "croatia",
      "serbia",
      "greece",
      "romania",
      "ukraine",
      "russia",
    ].includes(country)
  ) {
    return "Europe";
  }
  if (["brazil", "argentina", "chile", "uruguay", "paraguay", "peru", "ecuador", "colombia", "bolivia", "venezuela"].includes(country)) {
    return "South America";
  }
  if (["china", "japan", "republic of korea", "kazakhstan", "iraq", "saudi arabia", "qatar", "united arab emirates"].includes(country)) {
    return "Asia";
  }
  if (["australia", "new zealand"].includes(country)) {
    return "Oceania";
  }
  if (["usa", "canada", "mexico", "costa rica", "honduras", "guatemala", "panama"].includes(country)) {
    return "North America";
  }
  if (["algeria", "morocco", "tunisia", "egypt", "south africa", "nigeria", "ghana", "kenya"].includes(country)) {
    return "Africa";
  }
  if (country.startsWith("international")) {
    return "International";
  }
  return "Other";
}

export default function App() {
  const [tab, setTab] = useState<Tab>("history");
  const [dashboard, setDashboard] = useState<Dashboard | null>(null);
  const [predictions, setPredictions] = useState<Prediction[]>([]);
  const [htftPages, setHtftPages] = useState<Record<string, HtftOddsPage>>({});
  const [htftLoading, setHtftLoading] = useState(false);
  const htftPageRef = useRef<Record<string, number>>({});
  const [scores, setScores] = useState<ScoreRow[]>([]);
  const [jobHistory, setJobHistory] = useState<JobHistoryItem[]>([]);
  const [expandedRun, setExpandedRun] = useState<string | null>(null);
  const [status, setStatus] = useState("Yukleniyor...");
  const [busy, setBusy] = useState(false);
  const [settings, setSettings] = useState<PredictionSettings>({
    min_samples: 8,
    min_edge: 0.08,
    min_confidence_low: 0.45,
  });
  const [settingsDraft, setSettingsDraft] = useState({
    min_samples: "8",
    min_edge: "0.08",
    min_confidence_low: "0.45",
  });
  const [settingsDirty, setSettingsDirty] = useState(false);
  const [toasts, setToasts] = useState<{ id: number; message: string; tone: "info" | "warn" }[]>([]);

  const infoPopup = (message: string) => {
    const tone: "info" | "warn" = message.toLowerCase().includes("uyari") ? "warn" : "info";
    const id = Date.now() + Math.floor(Math.random() * 1000);
    setToasts((prev) => [...prev, { id, message, tone }]);
    window.setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
    }, 2600);
  };

  const syncDraftFromSettings = (next: PredictionSettings) => {
    setSettingsDraft({
      min_samples: String(next.min_samples),
      min_edge: String(next.min_edge),
      min_confidence_low: String(next.min_confidence_low),
    });
  };

  const parseDraftSettings = (): PredictionSettings | null => {
    const normalize = (v: string) => v.trim().replace(",", ".");
    const minSamples = Number(normalize(settingsDraft.min_samples));
    const minEdge = Number(normalize(settingsDraft.min_edge));
    const minConfidence = Number(normalize(settingsDraft.min_confidence_low));
    if (!Number.isFinite(minSamples) || !Number.isFinite(minEdge) || !Number.isFinite(minConfidence)) {
      return null;
    }
    return {
      min_samples: minSamples,
      min_edge: minEdge,
      min_confidence_low: minConfidence,
    };
  };

  const loadHtftBook = useCallback(async (bookmaker: string, page?: number) => {
    const targetPage = page ?? htftPageRef.current[bookmaker] ?? 1;
    setHtftLoading(true);
    try {
      const resp = await api<HtftOddsPage>(
        `/htft-odds?bookmaker=${encodeURIComponent(bookmaker)}&page=${targetPage}&page_size=${HTFT_PAGE_SIZE}`,
      );
      htftPageRef.current[bookmaker] = resp.page;
      setHtftPages((prev) => ({ ...prev, [bookmaker]: resp }));
    } finally {
      setHtftLoading(false);
    }
  }, []);

  const loadAllHtft = useCallback(async () => {
    await Promise.all(HTFT_SECTIONS.map((bookmaker) => loadHtftBook(bookmaker)));
  }, [loadHtftBook]);

  const loadBootstrap = useCallback(async () => {
    const bootstrap = await api<BootstrapPayload>("/bootstrap");
    const dash = bootstrap.dashboard;
    setDashboard(dash);
    if (dash.prediction_settings) {
      const next = {
        min_samples: dash.prediction_settings.min_samples,
        min_edge: dash.prediction_settings.min_edge,
        min_confidence_low: dash.prediction_settings.min_confidence_low,
        updated_at: dash.prediction_settings.updated_at,
      };
      setSettings(next);
      if (!settingsDirty) {
        syncDraftFromSettings(next);
      }
    }
    await Promise.resolve();
    setPredictions(bootstrap.predictions ?? []);
    setJobHistory(bootstrap.history ?? []);
    setScores(bootstrap.scores ?? []);
    return dash;
  }, [settingsDirty]);

  const refresh = useCallback(async (message = "Guncellendi.") => {
    const dash = await loadBootstrap();
    if (tab === "htft") {
      await loadAllHtft();
    }
    setStatus(message);
  }, [tab, loadAllHtft, loadBootstrap]);

  useEffect(() => {
    refresh().catch((e) => setStatus(e instanceof Error ? e.message : "Hata"));
  }, [refresh]);

  useEffect(() => {
    if (tab === "htft") {
      loadAllHtft().catch(() => undefined);
    }
  }, [tab, loadAllHtft]);

  useEffect(() => {
    if (!dashboard?.collector_running) return;
    const timer = window.setInterval(() => {
      refresh("Collector calisiyor...").catch(() => undefined);
    }, 4000);
    return () => window.clearInterval(timer);
  }, [dashboard?.collector_running, refresh]);

  async function runCollector() {
    setBusy(true);
    setStatus("Veri toplama baslatildi...");
    try {
      const started = await api<{ status: string; run_id: string }>("/collector/run?force=true", { method: "POST" });
      setTab("history");
      setStatus(`Collector basladi (${started.run_id.slice(0, 8)}...)`);
      for (let i = 0; i < 90; i++) {
        await new Promise((r) => setTimeout(r, 3000));
        const dash = await api<Dashboard>("/dashboard");
        setDashboard(dash);
        if (!dash.collector_running) {
          await refresh("Toplama tamamlandi.");
          break;
        }
        setStatus(`Toplaniyor... (${dash.collector?.request_count ?? 0} API istegi)`);
      }
    } catch (e) {
      setStatus(e instanceof Error ? e.message : "Collector baslatilamadi");
    } finally {
      setBusy(false);
    }
  }

  async function resetDb() {
    const confirmMessage =
      "DB SIFIRLAMA ONAYI\n\n" +
      "Silinecek tablolar:\n" +
      "- match_scores\n" +
      "- odds_snapshots\n" +
      "- matches\n" +
      "- provider_events\n\n" +
      "SILINMEYECEK:\n" +
      "- provider_sync_runs (job gecmisi)\n" +
      "- veritabani schema/migration tablolari\n\n" +
      "Bu islem geri alinamaz. Emin misin?";
    if (!window.confirm(confirmMessage)) return;
    setBusy(true);
    try {
      await api("/database/reset", { method: "POST" });
      await refresh("Database sifirlandi.");
    } catch (e) {
      setStatus(e instanceof Error ? e.message : "Reset basarisiz");
    } finally {
      setBusy(false);
    }
  }

  async function refreshPredictionsOnly() {
    setBusy(true);
    try {
      const preds = await api<{ items: Prediction[] }>("/predictions?limit=100");
      setPredictions(preds.items);
      setStatus(`Keskin tahminler guncellendi (${preds.items.length})`);
    } catch (e) {
      setStatus(e instanceof Error ? e.message : "Tahminler guncellenemedi");
    } finally {
      setBusy(false);
    }
  }

  async function savePredictionSettings() {
    setBusy(true);
    try {
      const parsed = parseDraftSettings();
      if (!parsed) {
        throw new Error("Ayar formati gecersiz. Sayi gir (ornek: 8, 0.05, 0.35)");
      }
      const updated = await api<PredictionSettings>("/prediction-settings", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(parsed),
      });
      setSettings(updated);
      syncDraftFromSettings(updated);
      setSettingsDirty(false);
      setStatus("Keskinlik ayarlari kaydedildi.");
    } catch (e) {
      setStatus(e instanceof Error ? e.message : "Ayarlar kaydedilemedi");
    } finally {
      setBusy(false);
    }
  }

  async function applySettingsAndPredict() {
    setBusy(true);
    try {
      const parsed = parseDraftSettings();
      if (!parsed) {
        throw new Error("Ayar formati gecersiz. Sayi gir (ornek: 8, 0.05, 0.35)");
      }
      const updated = await api<PredictionSettings>("/prediction-settings", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(parsed),
      });
      setSettings(updated);
      syncDraftFromSettings(updated);
      setSettingsDirty(false);
      const preds = await api<{ items: Prediction[] }>("/predictions?limit=30");
      setPredictions(preds.items);
      setStatus(`Ayarlar uygulandi, tahminler hesaplandi (${preds.items.length})`);
    } catch (e) {
      setStatus(e instanceof Error ? e.message : "Tahminler hesaplanamadi");
    } finally {
      setBusy(false);
    }
  }

  async function resetSettingsToSharpest() {
    setBusy(true);
    try {
      const updated = await api<PredictionSettings>("/prediction-settings/reset", { method: "POST" });
      setSettings(updated);
      syncDraftFromSettings(updated);
      setSettingsDirty(false);
      const preds = await api<{ items: Prediction[] }>("/predictions?limit=30");
      setPredictions(preds.items);
      setStatus(`Ayarlar en keskin varsayilana dondu (${preds.items.length} tahmin).`);
    } catch (e) {
      setStatus(e instanceof Error ? e.message : "Varsayilan ayara donulemedi");
    } finally {
      setBusy(false);
    }
  }

  const configured = dashboard?.provider?.configured === true;
  const showKeyWarning = dashboard != null && !configured;
  const pendingOddsSections = PENDING_ODDS_SECTIONS.map((section) => ({
    ...section,
    items: sortPredictionsByOdds(predictions.filter(section.match)),
  }));

  const htftBookmakerOrder = HTFT_SECTIONS;

  return (
    <div className="app">
      <div className="toastStack">
        {toasts.map((toast) => (
          <div key={toast.id} className={`toast ${toast.tone}`}>
            {toast.message}
          </div>
        ))}
      </div>
      <header className="hero">
        <div>
          <p className="eyebrow">Bet Agent · Quarkus</p>
          <h1>Odds Arsiv & Keskin Tahmin</h1>
          <p className="subtitle">
            Odds-API.io (Bet365/Sbobet ilk yari karsilikli gol + taraf) + Nesine HT/FT · {dashboard?.provider.enabled_providers?.join(", ") ?? "odds-api-io, nesine"}
            {dashboard?.provider.bookmakers ? ` · ${dashboard.provider.bookmakers}` : ""}
          </p>
        </div>
        <div className="actions">
          <button
            className="btn primary"
            disabled={busy || !configured || dashboard?.collector_running}
            onClick={() => {
              infoPopup("Bilgi: Manuel veri toplama sureci baslatiliyor.");
              runCollector();
            }}
          >
            {dashboard?.collector_running ? "Toplaniyor..." : "Manuel Veri Topla"}
          </button>
          <button
            className="btn ghost"
            disabled={busy}
            onClick={() => {
              infoPopup("Bilgi: Dashboard ve listeler yenileniyor.");
              refresh();
            }}
          >
            Yenile
          </button>
          <button
            className="btn danger"
            disabled={busy}
            onClick={() => {
              infoPopup("Uyari: Veritabani sifirlama islemi geri alinmaz.");
              resetDb();
            }}
          >
            DB Sifirla
          </button>
        </div>
      </header>

      {showKeyWarning && (
        <div className="alert">`.env` icine `ODDS_API_IO_KEY` ekle ve `docker compose up -d --build backend` calistir.</div>
      )}

      <p className="statusLine">{status}</p>

      <section className="stats">
        <article>
          <span>Mac arsivi</span>
          <strong>{dashboard?.matches ?? "-"}</strong>
        </article>
        <article>
          <span>Saatlik odds</span>
          <strong>{dashboard?.hourly_snapshots ?? "-"}</strong>
        </article>
        <article>
          <span>API limiti</span>
          <strong>
            {dashboard?.collector?.request_count != null
              ? `${dashboard.collector.request_count}/${dashboard.collector.request_budget ?? dashboard?.provider.request_budget}`
              : `${dashboard?.provider.usable_api_keys ?? dashboard?.provider.api_key_count ?? 1} key · ${dashboard?.provider.request_budget ?? 100}/saat`}
          </strong>
        </article>
        <article className={dashboard?.collector_running ? "pulse" : ""}>
          <span>Collector</span>
          <strong>{dashboard?.collector?.status ?? "bekliyor"}</strong>
        </article>
      </section>

      {dashboard?.data_quality && (
        <section className="panel qualityPanel">
          <div className="panelHead">
            <h2>Veri Kalitesi</h2>
            <span className="meta">
              FT &lt; HT: {dashboard.data_quality.impossible_ft_lt_ht} · IY eksik (settled):{" "}
              {dashboard.data_quality.settled_missing_ht_breakdown} · Skorlu mac:{" "}
              {dashboard.data_quality.scored_matches}
            </span>
          </div>
          <div className="qualityGrid">
            {dashboard.data_quality.history_by_market.map((row) => (
              <article key={row.market} className={row.market === "HTFT" ? "qualityHtft" : undefined}>
                <span>{marketQualityLabel(row.market)}</span>
                <strong>{row.pending_matches_with_odds}</strong>
                <small>bekleyen + odds</small>
                <strong className="qualitySecondary">{row.scored_matches_with_odds}</strong>
                <small>skorlu + odds</small>
              </article>
            ))}
            <article>
              <span>Tahmin bandi (n≥8)</span>
              <strong>{dashboard.data_quality.bands_with_min_samples_count}</strong>
              <small>hazir kombinasyon</small>
            </article>
          </div>
          {dashboard.data_quality.htft_pending_outcomes && dashboard.data_quality.htft_pending_outcomes.length > 0 && (
            <div className="qualityBands htftOutcomes">
              <span className="htftOutcomeHead">
                HT/FT orani olan mac: {dashboard.data_quality.htft_distinct_pending_matches ?? "—"} · Gercek sonucu
                1/2·2/1·1/X·2/X olan: {dashboard.data_quality.htft_settled_matches ?? 0}
              </span>
              <span className="htftOutcomeHint">
                Oran = bultende fiyat var · Sonuc = mac bitti ve skor o kombinasyona uyuyor
              </span>
              {dashboard.data_quality.htft_pending_outcomes.map((row) => (
                <span key={row.outcome}>
                  {row.outcome}: {row.pending_matches} mac orani · {row.settled_matches ?? 0} sonuc {row.outcome}
                </span>
              ))}
            </div>
          )}
          {dashboard.data_quality.by_provider && dashboard.data_quality.by_provider.length > 0 && (
            <div className="qualityByProvider">
              {dashboard.data_quality.by_provider.map((providerQuality) => {
                const htftMarket = providerQuality.history_by_market?.find((row) => row.market === "HTFT");
                return (
                  <article key={providerQuality.provider} className="qualityProviderBlock">
                    <h3>{providerQuality.provider}</h3>
                    <p className="meta">
                      HT/FT orani: {providerQuality.htft_distinct_pending_matches ?? htftMarket?.pending_matches_with_odds ?? 0} ·
                      skorlu mac: {providerQuality.scored_matches ?? 0} · HT/FT hedef sonuc: {providerQuality.htft_settled_matches ?? 0}
                    </p>
                    {providerQuality.htft_pending_outcomes && providerQuality.htft_pending_outcomes.length > 0 && (
                      <div className="qualityBands htftOutcomes">
                        {providerQuality.htft_pending_outcomes.map((row) => (
                          <span key={`${providerQuality.provider}-${row.outcome}`}>
                            {row.outcome}: {row.pending_matches} mac orani · {row.settled_matches ?? 0} sonuc {row.outcome}
                          </span>
                        ))}
                      </div>
                    )}
                  </article>
                );
              })}
            </div>
          )}
          {dashboard.data_quality.bands_with_min_samples.length > 0 && (
            <div className="qualityBands">
              {dashboard.data_quality.bands_with_min_samples.slice(0, 8).map((band) => (
                <span key={`${band.market}-${band.outcome}-${band.band}`}>
                  {band.market} {band.outcome} {band.band} (n={band.sample_count})
                </span>
              ))}
            </div>
          )}
        </section>
      )}

      <div className="tabs">
        <button
          className={tab === "history" ? "tab active" : "tab"}
          onClick={() => {
            infoPopup("Bilgi: Job gecmisi sekmesine geciliyor.");
            setTab("history");
          }}
        >
          Job Gecmisi
        </button>
        <button
          className={tab === "htft" ? "tab active" : "tab"}
          onClick={() => {
            infoPopup("Bilgi: HT/FT canli oranlar sekmesine geciliyor.");
            setTab("htft");
          }}
        >
          HT/FT Oranlar
        </button>
        <button
          className={tab === "predictions" ? "tab active" : "tab"}
          onClick={() => {
            infoPopup("Bilgi: Keskin tahminler sekmesine geciliyor.");
            setTab("predictions");
          }}
        >
          Keskin Tahminler
        </button>
        <button
          className={tab === "scores" ? "tab active" : "tab"}
          onClick={() => {
            infoPopup("Bilgi: Skorlar sekmesine geciliyor.");
            setTab("scores");
          }}
        >
          Skorlar
        </button>
      </div>

      {tab === "history" && (
        <section className="panel">
          <h2>Job Gecmisi</h2>
          <p className="hint">
            Saatlik odds collector ve Nesine skor job (10 dk) calismalarini buradan izle.
          </p>
          {jobHistory.length === 0 && <p className="empty">Henuz job yok. Manuel veri topla.</p>}
          <div className="jobList">
            {jobHistory.map((job) => {
              const open = expandedRun === job.run_id;
              const leagueTotalMatches = job.leagues.reduce((sum, l) => sum + leagueTotal(l), 0);
              return (
                <article key={job.run_id} className="jobCard">
                  <button
                    className="jobHeader"
                    onClick={() => {
                      infoPopup(open ? "Bilgi: Run detayi kapatiliyor." : "Bilgi: Run detayi aciliyor.");
                      setExpandedRun(open ? null : job.run_id);
                    }}
                  >
                    <div>
                      <span className={`statusBadge ${job.status}`}>{job.status}</span>
                      <strong>{formatDt(job.started_at)}</strong>
                      {job.provider && <span className="providerBadge">{job.provider}</span>}
                      <span className="meta">
                        {isScoreJob(job) ? (
                          <>
                            Skor job · {job.optimization?.trigger ?? "scheduled"} · {scoreJobSummary(job)}
                          </>
                        ) : (
                          <>
                            {job.events_seen} mac · {job.leagues.length} lig · {job.request_count}/{job.request_budget} API
                            {job.optimization?.odds_skipped_recent
                              ? ` · ${job.optimization.odds_skipped_recent} atlandi (yakin zamanda zaten alindi)`
                              : ""}
                          </>
                        )}
                      </span>
                      {formatFailureMessage(job.failure_message) && <p className="jobError">{formatFailureMessage(job.failure_message)}</p>}
                    </div>
                    <span className="chevron">{open ? "▲" : "▼"}</span>
                  </button>
                  {open && (
                    <div className="jobBody">
                      {isScoreJob(job) ? (
                        <div className="jobMeta">
                          <span>Tetikleyici: {job.optimization?.trigger ?? "-"}</span>
                          <span>Takip: {job.optimization?.tracked_total ?? job.events_seen}</span>
                          <span>LiveScore: {job.optimization?.from_live_score ?? 0}</span>
                          <span>Cross-provider: {job.optimization?.from_cross_provider ?? 0}</span>
                          <span>Bu run skor: {job.optimization?.settled_this_run ?? job.settled_events}</span>
                          <span>Odds bridge: {job.optimization?.bridged_odds ?? job.odds_snapshots_inserted}</span>
                          <span>Eksik skor: {job.optimization?.still_missing ?? job.pending_events}</span>
                          <span>Bitti: {formatDt(job.finished_at)}</span>
                        </div>
                      ) : (
                        <>
                          <div className="jobMeta">
                            <span>API: {job.request_count}/{job.request_budget}</span>
                            <span>Mod: {job.optimization?.odds_fetch_mode ?? "pending_only"}</span>
                            <span>Odds cekilen: {job.optimization?.odds_events_fetched ?? "-"}</span>
                            <span>Atlanan (yakin zamanda zaten alindi): {job.optimization?.odds_skipped_recent ?? 0}</span>
                            <span>Atlanan (run butcesi yetmedi): {job.optimization?.odds_skipped_budget ?? 0}</span>
                            <span>Settled: {job.settled_events}</span>
                            <span>Pending: {job.pending_events}</span>
                            <span>Bitti: {formatDt(job.finished_at)}</span>
                          </div>
                          {formatFailureMessage(job.failure_message) && (
                            <p className="jobError">Hata: {formatFailureMessage(job.failure_message)}</p>
                          )}
                          {job.leagues.length === 0 ? (
                            <p className="empty">Bu job icin lig detayi yok (eski kayit). Yeni toplama yapinca dolacak.</p>
                          ) : (
                            <table className="leagueTable">
                              <thead>
                                <tr>
                                  <th>Lig</th>
                                  <th>Biten</th>
                                  <th>Bekleyen</th>
                                  <th>Toplam</th>
                                </tr>
                              </thead>
                              <tbody>
                                {job.leagues.map((league) => (
                                  <tr key={league.league}>
                                    <td>{league.league}</td>
                                    <td>{league.settled}</td>
                                    <td>{league.pending}</td>
                                    <td>
                                      <strong>{leagueTotal(league)}</strong>
                                    </td>
                                  </tr>
                                ))}
                                <tr className="totalRow">
                                  <td>
                                    <strong>Toplam</strong>
                                  </td>
                                  <td>{job.leagues.reduce((s, l) => s + l.settled, 0)}</td>
                                  <td>{job.leagues.reduce((s, l) => s + l.pending, 0)}</td>
                                  <td>
                                    <strong>{leagueTotalMatches}</strong>
                                  </td>
                                </tr>
                              </tbody>
                            </table>
                          )}
                        </>
                      )}
                      {isScoreJob(job) && formatFailureMessage(job.failure_message) && (
                        <p className="jobError">Hata: {formatFailureMessage(job.failure_message)}</p>
                      )}
                    </div>
                  )}
                </article>
              );
            })}
          </div>
        </section>
      )}

      {dashboard?.providers && dashboard.providers.length > 0 && (
        <section className="providerStrip">
          {dashboard.providers.map((p) => (
            <article key={p.id}>
              <strong>{p.name}</strong>
              <span>{p.markets ?? p.bookmakers ?? p.source ?? p.id}</span>
            </article>
          ))}
        </section>
      )}

      {tab === "htft" && (
        <section className="panel">
          <div className="panelHead">
            <h2>HT/FT Canli Oranlar (Nesine)</h2>
            <span className="meta">
              HT/FT oranlari yalnizca Nesine bulteninden · 1/2 · 2/1 · 1/X · 2/X · {HTFT_PAGE_SIZE}/sayfa
            </span>
          </div>
          <p className="hint">
            Mac basina tek satir. Biten maclarda IY/MS skoru ve sonuc kodu gosterilir. HT/FT orani olmayan ama skoru
            hedef sonuclardan biri olan maclar &quot;Skor&quot; bolumunde listelenir.
          </p>
          {htftLoading && Object.keys(htftPages).length === 0 ? (
            <p className="empty">HT/FT oranlari yukleniyor...</p>
          ) : (
            htftBookmakerOrder.map((bookmaker) => {
              const pageData = htftPages[bookmaker];
              const rows = pageData?.items ?? [];
              const currentPage = pageData?.page ?? 1;
              const totalPages = pageData?.total_pages ?? 0;
              const total = pageData?.total ?? 0;
              if (!pageData && !htftLoading) {
                return null;
              }
              if (pageData && total === 0) {
                return (
                  <div key={bookmaker} className="htftBookBlock">
                    <h3 className="regionTitle">
                      {bookmaker === HTFT_SCORES_ONLY ? "Skor (oran yok)" : bookmaker} <span>0</span>
                    </h3>
                    <p className="empty">
                      {bookmaker === HTFT_SCORES_ONLY
                        ? "HT/FT orani olmayan hedef sonuclu mac yok."
                        : "Bu bookmaker icin uygun mac yok."}
                    </p>
                  </div>
                );
              }
              return (
                <div key={bookmaker} className="htftBookBlock">
                  <h3 className="regionTitle">
                    {bookmaker === HTFT_SCORES_ONLY ? "Skor (oran yok)" : bookmaker} <span>{total}</span>
                  </h3>
                  <table className="leagueTable htftTable">
                    <thead>
                      <tr>
                        <th>Mac</th>
                        <th>Lig</th>
                        <th>1/2</th>
                        <th>2/1</th>
                        <th>1/X</th>
                        <th>2/X</th>
                        <th>IY/MS Skor</th>
                        <th>Sonuc</th>
                        <th>Baslangic</th>
                      </tr>
                    </thead>
                    <tbody>
                      {rows.map((row) => (
                        <tr key={`${row.provider_match_id}-${row.bookmaker}`} className={row.status}>
                          <td>
                            {row.home_team} – {row.away_team}
                          </td>
                          <td>{row.competition_code}</td>
                          {HTFT_OUTCOMES.map((outcome) => (
                            <td
                              key={outcome}
                              className={`htftOddCell${row.htft_code === outcome ? " hit" : ""}`}
                            >
                              {formatOdd(row.odds[outcome])}
                            </td>
                          ))}
                          <td className="htftScoreCell">{formatHtftScore(row)}</td>
                          <td>
                            {row.status === "finished" && row.htft_code ? (
                              <strong className="htftResult">{row.htft_code.replace("/", " → ")}</strong>
                            ) : (
                              <span className="htftPending">Bekliyor</span>
                            )}
                          </td>
                          <td className="htftKickoffCell">{formatKickoff(row)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {totalPages > 1 && (
                    <div className="pagination">
                      <button
                        type="button"
                        disabled={currentPage <= 1 || htftLoading}
                        onClick={() => loadHtftBook(bookmaker, currentPage - 1)}
                      >
                        Onceki
                      </button>
                      <span>
                        Sayfa {currentPage} / {totalPages} · {total} mac
                      </span>
                      <button
                        type="button"
                        disabled={currentPage >= totalPages || htftLoading}
                        onClick={() => loadHtftBook(bookmaker, currentPage + 1)}
                      >
                        Sonraki
                      </button>
                    </div>
                  )}
                </div>
              );
            })
          )}
        </section>
      )}

      {tab === "predictions" && (
        <section className="panel">
          <div className="panelHead">
            <h2>Keskin Tahminler</h2>
            <div className="actions">
              <button className="btn ghost" disabled={busy} onClick={() => resetSettingsToSharpest()}>
                En Keskin Varsayilan
              </button>
              <button className="btn ghost" disabled={busy || !settingsDirty} onClick={() => savePredictionSettings()}>
                Ayarlari Kaydet
              </button>
              <button className="btn primary" disabled={busy} onClick={() => applySettingsAndPredict()}>
                Uygula ve Hesapla
              </button>
              <button className="btn ghost" disabled={busy} onClick={() => refreshPredictionsOnly()}>
                Yenile
              </button>
            </div>
          </div>
          <p className="hint">
            Nesine HT/FT (1/2 · 2/1 · 1/X · 2/X) + Odds-API.io ilk yari karsilikli gol + taraf (IY KG-VAR × IY 1X2). ★ = oyna onerisi (n≥8, edge≥8%, ROI&gt;0). En yuksek oran ustte.
          </p>
          <div className="predictionSettings">
            <label>
              Min ornek (n)
              <input
                value={settingsDraft.min_samples}
                onChange={(e) => {
                  setSettingsDraft((d) => ({ ...d, min_samples: e.target.value }));
                  setSettingsDirty(true);
                }}
              />
            </label>
            <label>
              Min edge
              <input
                value={settingsDraft.min_edge}
                onChange={(e) => {
                  setSettingsDraft((d) => ({ ...d, min_edge: e.target.value }));
                  setSettingsDirty(true);
                }}
              />
            </label>
            <label>
              Min Wilson alt sinir
              <input
                value={settingsDraft.min_confidence_low}
                onChange={(e) => {
                  setSettingsDraft((d) => ({ ...d, min_confidence_low: e.target.value }));
                  setSettingsDirty(true);
                }}
              />
            </label>
          </div>
          <div className="formulaBox">
            <strong>Formul</strong>
            <span>edge = hit_rate - (1 / oran)</span>
            <span>ROI = hit_rate × oran - 1</span>
            <span>Wilson alt sinir ≥ min_confidence_low ve edge ≥ min_edge ve n ≥ min_samples</span>
            {settings?.updated_at && <span>Son ayar: {formatDt(settings.updated_at)}</span>}
          </div>
          {pendingOddsSections.map((section) => (
            <div key={section.id} className="regionBlock">
              <h3 className="regionTitle">
                {section.title} <span>{section.items.length}</span>
              </h3>
              {section.items.length === 0 ? (
                <p className="empty">Bu kaynak icin keskin tahmin yok. Veri topla veya filtreleri gevset.</p>
              ) : (
                <div className="grid">
                  {section.items.map((p) => {
                    const playPick = isPlayPick(p, settings?.min_samples, settings?.min_edge);
                    return (
                    <article
                      key={`${p.provider_match_id}-${p.market}-${p.outcome}-${p.bookmaker}`}
                      className={`card tier-${p.confidence_tier ?? "dusuk"}${playPick ? " card-play" : ""}`}
                    >
                      {playPick && (
                        <div className="playStar" title="Oyna onerisi — n≥8, edge≥8%, pozitif ROI">
                          ★ OYNA
                        </div>
                      )}
                      <div className="cardTop">
                        <span className={`badge ${p.confidence_tier ?? "dusuk"}`}>
                          {p.confidence_tier ?? "dusuk"}
                        </span>
                        <span className="score">{p.decimal_odds.toFixed(2)}</span>
                      </div>
                      <h3>
                        {p.home_team} vs {p.away_team}
                      </h3>
                      <p className="pick">{marketLabel(p.market, p.outcome)}</p>
                      <p className="hint">
                        {p.competition_code} · {p.bookmaker} · {String(p.match_date).slice(0, 10)}
                      </p>
                      <dl>
                        <div>
                          <dt>Edge</dt>
                          <dd className={(p.edge ?? 0) >= 0 ? "pos" : "neg"}>{pct(p.edge ?? 0)}</dd>
                        </div>
                        <div>
                          <dt>ROI</dt>
                          <dd className={(p.historical_roi ?? 0) >= 0 ? "pos" : "neg"}>{pct(p.historical_roi ?? 0)}</dd>
                        </div>
                        <div>
                          <dt>Hit rate</dt>
                          <dd>{pct(p.hit_rate ?? 0)}</dd>
                        </div>
                        <div>
                          <dt>Wilson</dt>
                          <dd>{pct(p.confidence_low ?? 0)}</dd>
                        </div>
                        <div>
                          <dt>Ornek</dt>
                          <dd>
                            {p.hit_count ?? 0}/{p.sample_count ?? 0}
                          </dd>
                        </div>
                        <div>
                          <dt>Band</dt>
                          <dd>{p.odds_band ?? "—"}</dd>
                        </div>
                      </dl>
                      {p.rationale && <p className="hint">{p.rationale}</p>}
                    </article>
                    );
                  })}
                </div>
              )}
            </div>
          ))}
          {predictions.length === 0 && <p className="empty">Henuz tahmin yok. Veri topla.</p>}
        </section>
      )}

      {tab === "scores" && (
        <section className="panel">
          <h2>Skorlar</h2>
          <p className="hint">Biten maclarin ilk yari ve mac sonu skor/sonuc ozeti.</p>
          {scores.length === 0 ? (
            <p className="empty">Henuz skor yok. Collector ile biten maclari topla.</p>
          ) : (
            <table className="leagueTable">
              <thead>
                <tr>
                  <th>Tarih</th>
                  <th>Lig</th>
                  <th>Mac</th>
                  <th>IY</th>
                  <th>MS</th>
                  <th>IY Sonuc</th>
                  <th>MS Sonuc</th>
                  <th>HT/FT</th>
                  <th>1Y KG</th>
                  <th>1Y KG Taraf</th>
                </tr>
              </thead>
              <tbody>
                {scores.map((s) => (
                  <tr key={s.provider_match_id}>
                    <td>{String(s.match_date).slice(0, 10)}</td>
                    <td>{s.competition_code}</td>
                    <td>{s.home_team} vs {s.away_team}</td>
                    <td>{s.hthg}-{s.htag}</td>
                    <td>{s.fthg}-{s.ftag}</td>
                    <td>{s.ht_result}</td>
                    <td>{s.ft_result}</td>
                    <td>{s.htft_code}</td>
                    <td>{s.first_half_kg}</td>
                    <td>{s.first_half_kg_taraf_code}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      )}
    </div>
  );
}
