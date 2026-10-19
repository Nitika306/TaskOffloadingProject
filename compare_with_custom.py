#!/usr/bin/env python3
"""Compare two result CSVs row by row (same scheduler/topology/workload/seed) and per scheduler.
Usage: python3 compare_with_custom.py A.csv B.csv [labelA] [labelB]"""
import sys
import pandas as pd

a_path, b_path = sys.argv[1], sys.argv[2]
la = sys.argv[3] if len(sys.argv) > 3 else "A"
lb = sys.argv[4] if len(sys.argv) > 4 else "B"
a, b = pd.read_csv(a_path), pd.read_csv(b_path)
key = ["sched", "topo", "wl", "seed"]
m = a.merge(b, on=key, suffixes=("_a", "_b"))
print(f"{la} vs {lb}: {len(m)} matched rows ({len(a)} vs {len(b)})")
if m.empty:
    sys.exit("no matching rows: check topo/workload/seed ranges")
metrics = ["critDlRate", "dlRate", "meanResp", "p99Resp"]
rows = []
for s, g in m.groupby("sched"):
    r = {"sched": s}
    for k in metrics:
        r[k + "_A"] = g[k + "_a"].mean(); r[k + "_B"] = g[k + "_b"].mean()
        r[k + "_maxabs"] = (g[k + "_a"] - g[k + "_b"]).abs().max()
    rows.append(r)
t = pd.DataFrame(rows).set_index("sched")
pd.set_option("display.width", 250); pd.set_option("display.float_format", lambda x: f"{x:.4f}")
print("\nmeans over matched rows (A = " + la + ", B = " + lb + ") and the largest per-row absolute difference")
print(t[[c for k in metrics for c in (k + "_A", k + "_B", k + "_maxabs")]].to_string())
