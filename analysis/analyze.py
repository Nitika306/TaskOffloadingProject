#!/usr/bin/env python3
"""Tables, Wilcoxon tests and plots from the CSVs written by the Java simulator.
Usage: python3 analysis/analyze.py [results_dir]"""
import sys, os, itertools
import numpy as np, pandas as pd
from scipy import stats
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

R = sys.argv[1] if len(sys.argv) > 1 else "results"
OUT_T, OUT_F = os.path.join(R, "tables"), os.path.join(R, "figures")
os.makedirs(OUT_T, exist_ok=True); os.makedirs(OUT_F, exist_ok=True)

ORDER = ["FCFS", "RR", "GREEDY", "PSO", "GWO", "WOA", "BASE19", "PROPOSED_EXACT", "PROPOSED",
         "ABL_NOGATE", "ABL_NOLOAD", "ABL_NOTIER", "ABL_GATECLOUD"]
# metric: (column, label, higher_is_better)
M = {"critDlRate": ("critDlRate", "Critical-deadline rate", True),
     "dlRate": ("dlRate", "Overall deadline rate", True),
     "meanResp": ("meanResp", "Mean response time (s)", False),
     "p99Resp": ("p99Resp", "p99 response time (s)", False),
     "energyPerTask": ("energyPerTask", "Energy per task (J)", False),
     "decisionUs": ("decisionUs", "Decision time (us/task)", False),
     "utilCV": ("utilCV", "Utilisation CV (load spread)", False)}
WLS = ["P30", "P60", "P90", "B60"]

def ci95(x):
    x = np.asarray(x, float); n = len(x)
    return stats.t.ppf(0.975, n - 1) * x.std(ddof=1) / np.sqrt(n) if n > 1 else 0.0

def holm(p):
    p = np.asarray(p, float); m = len(p); o = np.argsort(p); adj = np.empty(m); run = 0.0
    for r, i in enumerate(o):
        run = max(run, (m - r) * p[i]); adj[i] = min(1.0, run)
    return adj

def fmt_ms(mean, ci, dec): return f"{mean:.{dec}f} ± {ci:.{dec}f}"

main = pd.read_csv(os.path.join(R, "main.csv"))
nseeds = main.seed.nunique()

# ---------- 1. summary tables (mean ± 95% CI over seeds) ----------
rows = []
for (topo, wl, sc), g in main.groupby(["topo", "wl", "sched"]):
    r = {"topo": topo, "wl": wl, "sched": sc, "n_seeds": g.seed.nunique()}
    for k, (col, _, _) in M.items():
        r[k + "_mean"] = g[col].mean(); r[k + "_ci"] = ci95(g[col])
    for c in ["fracEdge", "fracReg", "fracCloud"]: r[c] = g[c].mean()
    rows.append(r)
summ = pd.DataFrame(rows); summ["o"] = summ.sched.map({s: i for i, s in enumerate(ORDER)})
summ = summ.sort_values(["topo", "wl", "o"]).drop(columns="o")
summ.to_csv(os.path.join(OUT_T, "summary_all.csv"), index=False)

def latex_table(topo, wl, caption):
    g = summ[(summ.topo == topo) & (summ.wl == wl)].set_index("sched").loc[ORDER]
    cols = [("critDlRate", 3), ("dlRate", 3), ("meanResp", 3), ("p99Resp", 3), ("energyPerTask", 2), ("decisionUs", 1)]
    head = " & ".join(["Scheduler"] + [M[c][1] for c, _ in cols])
    lines = [r"\begin{table*}[t]", r"\centering", rf"\caption{{{caption}}}", r"\footnotesize",
             r"\begin{tabular}{l" + "r" * len(cols) + "}", r"\hline", head.replace("%", r"\%").replace("_", r"\_") + r" \\", r"\hline"]
    for sc in ORDER:
        cells = [fmt_ms(g.loc[sc, c + "_mean"], g.loc[sc, c + "_ci"], d) for c, d in cols]
        lines.append(sc.replace("_", r"\_") + " & " + " & ".join(cells) + r" \\")
    lines += [r"\hline", r"\end{tabular}", r"\end{table*}"]
    open(os.path.join(OUT_T, f"table_{topo}_{wl}.tex"), "w").write("\n".join(lines))
    md = ["| Scheduler | " + " | ".join(M[c][1] for c, _ in cols) + " |", "|---|" + "---|" * len(cols)]
    for sc in ORDER:
        md.append(f"| {sc} | " + " | ".join(fmt_ms(g.loc[sc, c + "_mean"], g.loc[sc, c + "_ci"], d) for c, d in cols) + " |")
    open(os.path.join(OUT_T, f"table_{topo}_{wl}.md"), "w").write("\n".join(md))

for topo in ["tiered", "flat"]:
    for wl in WLS:
        latex_table(topo, wl, f"Mean $\\pm$ 95\\% CI over {nseeds} seeds, {topo} topology, workload {wl}.")

# ---------- 2. Wilcoxon signed-rank: PROPOSED vs every other scheduler, paired by seed ----------
wrows = []
for topo, wl in itertools.product(["tiered", "flat"], WLS):
    g = main[(main.topo == topo) & (main.wl == wl)]
    for mk, (col, lab, hib) in M.items():
        piv = g.pivot(index="seed", columns="sched", values=col)
        others = [s for s in ORDER if s != "PROPOSED"]
        pv, recs = [], []
        for o in others:
            d = (piv["PROPOSED"] - piv[o]).dropna().values
            if len(d) == 0 or np.allclose(d, 0): p = 1.0; stat = 0.0
            else:
                try: stat, p = stats.wilcoxon(d, zero_method="wilcox", alternative="two-sided")
                except ValueError: stat, p = 0.0, 1.0
            better = (np.median(d) > 0) if hib else (np.median(d) < 0)
            direction = "no difference" if np.allclose(d, 0) else ("PROPOSED better" if better else "PROPOSED worse")
            pv.append(p)
            recs.append(dict(topo=topo, wl=wl, metric=mk, vs=o, n_pairs=len(d), median_diff=np.median(d), mean_diff=np.mean(d),
                             W=stat, p=p, direction=direction))
        adj = holm(pv)
        for r, a in zip(recs, adj):
            r["p_holm"] = a; r["significant_0.05"] = bool(a < 0.05); wrows.append(r)
W = pd.DataFrame(wrows); W.to_csv(os.path.join(OUT_T, "wilcoxon_proposed_vs_others.csv"), index=False)

# compact view for the headline metrics
for mk in ["critDlRate", "dlRate", "meanResp"]:
    sub = W[W.metric == mk].copy()
    sub["cell"] = np.where(sub["significant_0.05"], sub.direction.map({"PROPOSED better": "+", "PROPOSED worse": "-", "no difference": "="}), "ns")
    tab = sub.pivot_table(index="vs", columns=["topo", "wl"], values="cell", aggfunc="first").reindex([s for s in ORDER if s != "PROPOSED"])
    tab.to_csv(os.path.join(OUT_T, f"wilcoxon_signs_{mk}.csv"))

# ---------- 3. figures ----------
plt.rcParams.update({"font.size": 8, "axes.spines.top": False, "axes.spines.right": False, "figure.dpi": 150})
cmap = plt.get_cmap("tab20")
COL = {s: cmap(i) for i, s in enumerate(ORDER)}

def grouped(metric, fname, logy=False):
    col, lab, _ = M[metric]
    fig, axs = plt.subplots(1, 2, figsize=(11, 3.4), sharey=True)
    for ax, topo in zip(axs, ["tiered", "flat"]):
        w = 0.8 / len(ORDER)
        for j, sc in enumerate(ORDER):
            mu, ci = [], []
            for wl in WLS:
                x = main[(main.topo == topo) & (main.wl == wl) & (main.sched == sc)][col]
                mu.append(x.mean()); ci.append(ci95(x))
            ax.bar(np.arange(len(WLS)) + j * w, mu, w, yerr=ci, color=COL[sc], label=sc, capsize=1, error_kw={"lw": 0.5})
        ax.set_xticks(np.arange(len(WLS)) + 0.4 - w / 2); ax.set_xticklabels(WLS); ax.set_title(f"{topo} topology"); ax.set_ylabel(lab)
        if logy: ax.set_yscale("log")
    axs[1].legend(ncol=1, fontsize=6, bbox_to_anchor=(1.01, 1), loc="upper left", frameon=False)
    fig.tight_layout(); fig.savefig(os.path.join(OUT_F, fname), dpi=200); plt.close(fig)

grouped("critDlRate", "fig_critical_deadline_rate.png")
grouped("dlRate", "fig_overall_deadline_rate.png")
grouped("meanResp", "fig_mean_response.png", logy=True)
grouped("decisionUs", "fig_decision_time.png", logy=True)

# tier distribution
fig, axs = plt.subplots(1, 2, figsize=(11, 3.2), sharey=True)
for ax, wl in zip(axs, ["P60", "B60"]):
    g = summ[(summ.topo == "tiered") & (summ.wl == wl)].set_index("sched").loc[ORDER]
    b = np.zeros(len(ORDER))
    for c, lab, colr in [("fracEdge", "edge", "#4c78a8"), ("fracReg", "regional", "#f58518"), ("fracCloud", "cloud", "#54a24b")]:
        ax.bar(range(len(ORDER)), g[c].values, bottom=b, label=lab, color=colr); b += g[c].values
    ax.set_xticks(range(len(ORDER))); ax.set_xticklabels(ORDER, rotation=60, ha="right"); ax.set_title(f"Task placement by tier, tiered, {wl}")
axs[0].set_ylabel("fraction of tasks"); axs[1].legend(frameon=False)
fig.tight_layout(); fig.savefig(os.path.join(OUT_F, "fig_tier_distribution.png"), dpi=200); plt.close(fig)

# per-seed boxplots of critical-deadline rate (tiered, B60)
fig, ax = plt.subplots(figsize=(7, 3.2))
g = main[(main.topo == "tiered") & (main.wl == "B60")]
ax.boxplot([g[g.sched == s].critDlRate.values for s in ORDER], tick_labels=ORDER, showfliers=True)
ax.set_xticklabels(ORDER, rotation=60, ha="right"); ax.set_ylabel("Critical-deadline rate"); ax.set_title("Distribution over seeds, tiered, B60")
fig.tight_layout(); fig.savefig(os.path.join(OUT_F, "fig_box_critical_B60.png"), dpi=200); plt.close(fig)

# WAN sweep
wp = os.path.join(R, "wan.csv")
if os.path.exists(wp):
    wan = pd.read_csv(wp); wan["wan"] = wan.scenario.str.replace("wan=", "").astype(float) * 1000
    fig, axs = plt.subplots(1, 3, figsize=(11, 3.2))
    for ax, (col, lab) in zip(axs, [("critDlRate", "Critical-deadline rate"), ("dlRate", "Overall deadline rate"), ("meanResp", "Mean response time (s)")]):
        for sc in wan.sched.unique():
            g = wan[wan.sched == sc].groupby("wan")[col]
            ax.errorbar(g.mean().index, g.mean().values, yerr=[ci95(wan[(wan.sched == sc) & (wan.wan == w)][col]) for w in g.mean().index],
                        marker="o", ms=3, lw=1, label=sc, color=COL[sc], capsize=1)
        ax.set_xscale("log"); ax.set_xlabel("one-way cloud delay (ms)"); ax.set_ylabel(lab)
    axs[2].legend(fontsize=6, frameon=False); fig.tight_layout(); fig.savefig(os.path.join(OUT_F, "fig_wan_sweep.png"), dpi=200); plt.close(fig)
    wan.groupby(["wan", "sched"])[["critDlRate", "dlRate", "meanResp"]].agg(["mean"]).round(4).to_csv(os.path.join(OUT_T, "wan_summary.csv"))

# sensitivity
sp = os.path.join(R, "sens.csv")
if os.path.exists(sp):
    sens = pd.read_csv(sp)
    rows = []
    for (sc, wl), g in sens.groupby(["scenario", "wl"]):
        rows.append(dict(variant=sc, wl=wl, dlRate=g.dlRate.mean(), dlRate_ci=ci95(g.dlRate), critDlRate=g.critDlRate.mean(),
                         critDlRate_ci=ci95(g.critDlRate), meanResp=g.meanResp.mean()))
    S = pd.DataFrame(rows); S.to_csv(os.path.join(OUT_T, "sensitivity_summary.csv"), index=False)
    fig, axs = plt.subplots(1, 2, figsize=(10, 4.4), sharey=True)
    for ax, wl in zip(axs, ["P60", "B60"]):
        g = S[S.wl == wl].sort_values("dlRate"); y = np.arange(len(g))
        ax.errorbar(g.dlRate, y, xerr=g.dlRate_ci, fmt="o", ms=3, lw=0.8)
        ax.axvline(S[(S.wl == wl) & (S.variant == "basex1.0")].dlRate.iloc[0], color="r", lw=0.8, ls="--")
        ax.set_yticks(y); ax.set_yticklabels(g.variant); ax.set_xlabel("Overall deadline rate"); ax.set_title(f"PROPOSED sensitivity, {wl}")
    fig.tight_layout(); fig.savefig(os.path.join(OUT_F, "fig_sensitivity.png"), dpi=200); plt.close(fig)

# tuning trace
tp = os.path.join(R, "tuning_log.csv")
if os.path.exists(tp):
    t = pd.read_csv(tp); fig, ax = plt.subplots(figsize=(4.5, 3))
    ax.plot(t.cand, t.J, ".", ms=4, alpha=.5, label="candidate"); ax.plot(t.cand, t.J.cummin(), "-", lw=1.2, label="best so far")
    ax.set_xlabel("candidate (0 = default)"); ax.set_ylabel("tuning objective J (lower better)"); ax.legend(frameon=False)
    fig.tight_layout(); fig.savefig(os.path.join(OUT_F, "fig_tuning.png"), dpi=200); plt.close(fig)

# ---------- 4. headline console summary ----------
print(f"\n{nseeds} seeds per cell. Headline (tiered):")
for wl in ["P60", "P90", "B60"]:
    g = summ[(summ.topo == "tiered") & (summ.wl == wl)].set_index("sched").loc[ORDER]
    print(f"\n[{wl}]"); print(g[["critDlRate_mean", "dlRate_mean", "meanResp_mean", "decisionUs_mean"]].round(3).to_string())
print("\nWilcoxon (Holm) PROPOSED vs others, critDlRate, tiered B60:")
print(W[(W.metric == "critDlRate") & (W.topo == "tiered") & (W.wl == "B60")][["vs", "median_diff", "p_holm", "direction"]].round(4).to_string(index=False))
