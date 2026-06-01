#!/usr/bin/env python3
"""Compare Nesine HT/FT finished match scores vs Odds-API.io warehouse scores."""
import json
import re
import urllib.request
from pathlib import Path

TARGET = {"1/2", "2/1", "1/X", "2/X"}
API = "http://localhost:8080/api"


def norm(s: str) -> str:
    if not s:
        return ""
    t = s.lower()
    for a, b in (
        ("ı", "i"),
        ("İ", "i"),
        ("ş", "s"),
        ("Ş", "s"),
        ("ğ", "g"),
        ("Ğ", "g"),
        ("ü", "u"),
        ("Ü", "u"),
        ("ö", "o"),
        ("Ö", "o"),
        ("ç", "c"),
        ("Ç", "c"),
    ):
        t = t.replace(a, b)
    return re.sub(r"[^a-z0-9]+", "", t)


def htft_code(hthg, htag, fthg, ftag) -> str:
    def side(h, a):
        if h > a:
            return "1"
        if h < a:
            return "2"
        return "X"

    return f"{side(hthg, htag)}/{side(fthg, ftag)}"


def match_key(home, away, date) -> str:
    return f"{norm(home)}|{norm(away)}|{str(date)[:10]}"


NOISE = {
    "fc", "fk", "sk", "cf", "sc", "ac", "afc", "as", "bk", "if", "ff", "sv",
    "united", "city", "town", "club", "kulubu", "spor", "basketbol", "basketball",
    "team", "the", "de", "la", "real", "sporting",
}


def tokens(name: str) -> list[str]:
    import re as _re

    t = name.lower()
    for a, b in (
        ("ı", "i"), ("ş", "s"), ("ğ", "g"), ("ü", "u"), ("ö", "o"), ("ç", "c"),
    ):
        t = t.replace(a, b)
    parts = [p for p in _re.split(r"[^a-z0-9]+", t) if len(p) >= 2 and p not in NOISE]
    return parts or ([norm(name)] if norm(name) else [])


def _lev_ratio(a: str, b: str) -> float:
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


def side_similarity(left: str, right: str) -> float:
    ta, tb = tokens(left), tokens(right)
    if not ta or not tb:
        return 0.0
    blob = _lev_ratio("".join(ta), "".join(tb))

    def avg_best(src, dst):
        return sum(max(_lev_ratio(s, d) for d in dst) for s in src) / len(src)

    return max(blob, avg_best(ta, tb), avg_best(tb, ta))


def pair_similarity(h1, a1, h2, a2) -> float:
    return (side_similarity(h1, h2) + side_similarity(a1, a2)) / 2.0


def find_odds_api_match(nesine_row, odds_rows):
    date = str(nesine_row.get("match_date"))[:10]
    scored = []
    for o in odds_rows:
        if str(o.get("match_date"))[:10] != date:
            continue
        sim = pair_similarity(
            nesine_row["home_team"],
            nesine_row["away_team"],
            o["home_team"],
            o["away_team"],
        )
        if sim >= 0.74:
            scored.append((sim, o))
    if not scored:
        return None
    scored.sort(key=lambda x: x[0], reverse=True)
    if len(scored) == 1:
        return scored[0][1]
    if scored[0][0] - scored[1][0] >= 0.06:
        return scored[0][1]
    return None


def fetch_json(url: str):
    with urllib.request.urlopen(url, timeout=120) as r:
        return json.loads(r.read())


def load_nesine_finished():
    rows = []
    page = 1
    while True:
        data = fetch_json(
            f"{API}/htft-odds?bookmaker=Nesine&page={page}&page_size=20"
        )
        for item in data["items"]:
            if item.get("status") == "finished":
                rows.append(item)
        if page >= data["total_pages"]:
            break
        page += 1
    return rows


def load_odds_api_scores():
    data = fetch_json(f"{API}/scores?limit=5000")
    return [x for x in data["items"] if x.get("provider") == "Odds-API.io"]


def main():
    nesine = load_nesine_finished()
    odds_api = load_odds_api_scores()

    matched = 0
    mismatches = []
    target_diff = []
    no_oa = []

    for m in nesine:
        o = find_odds_api_match(m, odds_api)
        if not o:
            no_oa.append(m)
            continue
        matched += 1
        our_code = m.get("htft_code")
        oa_code = o["htft_code"]
        same_scores = (
            m["hthg"] == o["hthg"]
            and m["htag"] == o["htag"]
            and m["fthg"] == o["fthg"]
            and m["ftag"] == o["ftag"]
        )
        if not same_scores or our_code != oa_code:
            mismatches.append(
                {
                    "match": f"{m['home_team']} vs {m['away_team']} ({m.get('match_date')})",
                    "our": f"HT {m['hthg']}-{m['htag']} FT {m['fthg']}-{m['ftag']} -> {our_code}",
                    "oa": f"HT {o['hthg']}-{o['htag']} FT {o['fthg']}-{o['ftag']} -> {oa_code}",
                    "our_target": our_code in TARGET,
                    "oa_target": oa_code in TARGET,
                }
            )
        if (our_code in TARGET) != (oa_code in TARGET):
            target_diff.append(mismatches[-1])

    our_target = sum(1 for m in nesine if m.get("htft_code") in TARGET)
    oa_target = sum(
        1
        for m in nesine
        if (o := find_odds_api_match(m, odds_api)) and o["htft_code"] in TARGET
    )

    print(f"Nesine finished (HT/FT odds): {len(nesine)}")
    print(f"Matched Odds-API.io scores: {matched}")
    print(f"No Odds-API.io match: {len(no_oa)}")
    print(f"Score/code mismatches: {len(mismatches)}")
    print(f"TARGET (1/2,2/1,1/X,2/X) — Nesine: {our_target}, Odds-API (matched): {oa_target}")
    print(f"TARGET outcome disagreements: {len(target_diff)}")

    from collections import Counter

    print("\nNesine htft_code distribution:")
    for code, cnt in Counter(m.get("htft_code") for m in nesine).most_common():
        mark = " <-- TARGET" if code in TARGET else ""
        print(f"  {code}: {cnt}{mark}")

    if target_diff:
        print("\nMatches where TARGET flag differs (Nesine vs Odds-API):")
        for mm in target_diff:
            print(f"  {mm['match']}")
            print(f"    {mm['our']}")
            print(f"    {mm['oa']}")

    if mismatches:
        print("\nAll score mismatches:")
        for mm in mismatches:
            print(f"  {mm['match']}")
            print(f"    {mm['our']}")
            print(f"    {mm['oa']}")

    out = Path(__file__).resolve().parent / "htft_verify_report.json"
    out.write_text(
        json.dumps(
            {
                "nesine_finished": len(nesine),
                "matched": matched,
                "no_oa": len(no_oa),
                "mismatches": mismatches,
                "target_diff": target_diff,
                "our_target": our_target,
                "oa_target": oa_target,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    suspicious = [
        m
        for m in nesine
        if m["hthg"] == m["fthg"]
        and m["htag"] == m["ftag"]
        and not (m["hthg"] == 0 and m["htag"] == 0)
    ]
    print(f"\nSuspicious (HT total equals FT, not 0-0): {len(suspicious)}")
    for m in suspicious[:10]:
        print(
            f"  {m['home_team']} vs {m['away_team']}: "
            f"HT {m['hthg']}-{m['htag']} FT {m['fthg']}-{m['ftag']} -> {m.get('htft_code')}"
        )

    print(f"\nReport: {out}")


if __name__ == "__main__":
    main()
