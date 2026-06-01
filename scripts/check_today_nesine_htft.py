#!/usr/bin/env python3
"""Check today's Nesine HT/FT finished scores vs Odds-API.io."""
import json
import re
import urllib.request
from datetime import date

API = "http://localhost:8080/api"
TODAY = date.today().isoformat()
TARGET = {"1/2", "2/1", "1/X", "2/X"}

NOISE = {
    "fc", "fk", "sk", "cf", "sc", "ac", "afc", "as", "bk", "if", "ff", "sv",
    "united", "city", "town", "club", "kulubu", "spor", "basketbol", "basketball",
    "team", "the", "de", "la", "real", "sporting",
}


def fetch(url: str):
    with urllib.request.urlopen(url, timeout=120) as r:
        return json.loads(r.read())


def tokens(name: str) -> list[str]:
    t = name.lower()
    for a, b in (("ı", "i"), ("ş", "s"), ("ğ", "g"), ("ü", "u"), ("ö", "o"), ("ç", "c")):
        t = t.replace(a, b)
    parts = [p for p in re.split(r"[^a-z0-9]+", t) if len(p) >= 2 and p not in NOISE]
    return parts or [re.sub(r"[^a-z0-9]+", "", t)]


def lev(a: str, b: str) -> float:
    if not a or not b:
        return 0.0
    if a == b:
        return 1.0
    if len(a) >= 4 and len(b) >= 4 and (a.startswith(b) or b.startswith(a) or a in b or b in a):
        return 0.9
    la, lb = len(a), len(b)
    prev = list(range(lb + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cost = 0 if ca == cb else 1
            cur.append(min(cur[-1] + 1, prev[j] + 1, prev[j - 1] + cost))
        prev = cur
    return 1.0 - prev[-1] / max(la, lb)


def side_sim(left: str, right: str) -> float:
    ta, tb = tokens(left), tokens(right)
    if not ta or not tb:
        return 0.0
    blob = lev("".join(ta), "".join(tb))

    def avg_best(src, dst):
        return sum(max(lev(s, d) for d in dst) for s in src) / len(src)

    return max(blob, avg_best(ta, tb), avg_best(tb, ta))


def pair_sim(h1, a1, h2, a2) -> float:
    return (side_sim(h1, h2) + side_sim(a1, a2)) / 2.0


def find_oa(match, oa_rows):
    scored = []
    for o in oa_rows:
        if str(o.get("match_date"))[:10] != TODAY:
            continue
        sim = pair_sim(match["home_team"], match["away_team"], o["home_team"], o["away_team"])
        if sim >= 0.74:
            scored.append((sim, o))
    if not scored:
        return None
    scored.sort(key=lambda x: x[0], reverse=True)
    if len(scored) == 1 or scored[0][0] - scored[1][0] >= 0.06:
        return scored[0][1]
    return None


def main():
    rows = []
    page = 1
    while True:
        data = fetch(f"{API}/htft-odds?bookmaker=Nesine&page={page}&page_size=50")
        for item in data["items"]:
            md = str(item.get("match_date") or "")[:10]
            if md == TODAY and item.get("status") == "finished":
                rows.append(item)
        if page >= data["total_pages"]:
            break
        page += 1

    oa = [x for x in fetch(f"{API}/scores?limit=5000")["items"] if x.get("provider") == "Odds-API.io"]

    ok = 0
    mismatch = []
    no_oa = []
    invalid = []
    pending_today = 0

    page = 1
    while True:
        data = fetch(f"{API}/htft-odds?bookmaker=Nesine&page={page}&page_size=50")
        for item in data["items"]:
            if str(item.get("match_date") or "")[:10] == TODAY and item.get("status") == "pending":
                pending_today += 1
        if page >= data["total_pages"]:
            break
        page += 1

    for m in rows:
        hthg, htag, fthg, ftag = m.get("hthg"), m.get("htag"), m.get("fthg"), m.get("ftag")
        if None in (hthg, htag, fthg, ftag):
            invalid.append(m)
            continue
        if fthg < hthg or ftag < htag:
            invalid.append(m)
            continue
        o = find_oa(m, oa)
        if not o:
            no_oa.append(m)
            continue
        same = hthg == o["hthg"] and htag == o["htag"] and fthg == o["fthg"] and ftag == o["ftag"]
        if same:
            ok += 1
        else:
            mismatch.append((m, o))

    print(f"Tarih: {TODAY}")
    print(f"Nesine HT/FT bitmis (bugun): {len(rows)}")
    print(f"Nesine HT/FT bekleyen (bugun): {pending_today}")
    matched = len(rows) - len(no_oa) - len(invalid)
    print(f"Odds-API eslesen: {matched}")
    print(f"Skor uyumlu: {ok}")
    print(f"Uyumsuz: {len(mismatch)}")
    print(f"OA referans yok: {len(no_oa)}")
    print(f"Gecersiz/eksik skor: {len(invalid)}")

    target = [m for m in rows if m.get("htft_code") in TARGET]
    print(f"Hedef sonuc (1/2,2/1,1/X,2/X): {len(target)}")

    if mismatch:
        print("\n--- UYUMSUZ ---")
        for m, o in mismatch:
            print(f"{m['home_team']} vs {m['away_team']}")
            print(
                f"  Nesine: IY {m['hthg']}-{m['htag']} MS {m['fthg']}-{m['ftag']} -> {m.get('htft_code')}"
            )
            print(
                f"  OA:     IY {o['hthg']}-{o['htag']} MS {o['fthg']}-{o['ftag']} -> {o['htft_code']}"
            )

    if no_oa:
        print("\n--- OA YOK (tum) ---")
        for m in no_oa:
            print(
                f"{m['home_team']} vs {m['away_team']} | "
                f"IY {m.get('hthg')}-{m.get('htag')} MS {m.get('fthg')}-{m.get('ftag')} -> {m.get('htft_code')}"
            )

    if invalid:
        print("\n--- GECERSIZ ---")
        for m in invalid:
            print(f"{m['home_team']} vs {m['away_team']} {m}")


if __name__ == "__main__":
    main()
