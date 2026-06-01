import json
import os
from collections import Counter

path = os.path.join(os.environ.get("TEMP", "."), "nesine-ls.json")
data = json.loads(open(path, encoding="utf-8").read())
rows = [r for r in data.get("d", []) if r.get("S") == 4]

def periods(r):
    return {p["T"]: (p.get("H"), p.get("A")) for p in r.get("ES", [])}

mismatch_t19_t1 = 0
derivable = 0
t19_eq_t1 = 0
for r in rows:
    p = periods(r)
    t1 = p.get(1)
    t19 = p.get(19)
    t2 = p.get(2)
    if not t1 or not t19:
        continue
    if t19 == t1:
        t19_eq_t1 += 1
    else:
        mismatch_t19_t1 += 1
    if t1 and t2:
        dh, da = t1[0] - t2[0], t1[1] - t2[1]
        if dh >= 0 and da >= 0 and (dh, da) != t19:
            derivable += 1
            if len([x for x in [mismatch_t19_t1] if x]) < 5:
                pass

print("finished rows", len(rows))
print("T19==T1", t19_eq_t1, "T19!=T1", mismatch_t19_t1)
print("T2 derivable HT != T19", derivable)
print("period types", Counter(t for r in rows for t in periods(r)))

# show examples where T2 derives different HT than T19
examples = []
for r in rows[:500]:
    p = periods(r)
    t1, t19, t2 = p.get(1), p.get(19), p.get(2)
    if not (t1 and t19 and t2):
        continue
    dh, da = t1[0] - t2[0], t1[1] - t2[1]
    if dh >= 0 and da >= 0 and (dh, da) != t19:
        examples.append((r.get("HTTR"), r.get("ATTR"), t19, t1, t2, (dh, da)))
print("derive != T19 examples", len(examples))
for ex in examples[:8]:
    print(" ", ex)
