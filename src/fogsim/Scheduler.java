package fogsim;

import fogsim.Node.Tier;
import java.util.*;

/** All offloading policies compared in the study. */
public final class Scheduler {
    public enum Kind {
        FCFS, RR, GREEDY,                                  // classical / simple baselines
        PSO, GWO, WOA,                                     // single swarms (full fitness, equal 600-evaluation budget)
        BASE19,                                            // base GWO-WOA hybrid [19]: load-blind, tier-blind, no gate
        PROPOSED, PROPOSED_EXACT,                          // proposed blueprint; exact argmin instead of swarm search
        ABL_NOGATE, ABL_NOLOAD, ABL_NOTIER, ABL_GATECLOUD  // ablations
    }

    public final Kind kind; private int rr = 0;
    public Scheduler(Kind k) { kind = k; }

    /** Gate with reserved edge lanes active. */
    public boolean gate() { return kind == Kind.PROPOSED || kind == Kind.PROPOSED_EXACT || kind == Kind.ABL_NOLOAD || kind == Kind.ABL_NOTIER; }
    boolean useLoad() { return kind != Kind.BASE19 && kind != Kind.ABL_NOLOAD; }
    boolean useTier() { return kind != Kind.BASE19 && kind != Kind.ABL_NOTIER; }

    static Node[] feasible(Task t, Node[] nodes) {
        int c = 0; for (Node v : nodes) if (v.ram >= t.mem && v.bw >= t.bwReq) c++;
        if (c == 0) return nodes;
        Node[] out = new Node[c]; int k = 0;
        for (Node v : nodes) if (v.ram >= t.mem && v.bw >= t.bwReq) out[k++] = v;
        Arrays.sort(out, Comparator.comparingDouble((Node v) -> v.delta).thenComparingInt(v -> v.id));
        return out;
    }

    public Node assign(Task t, Node[] nodes, double now, Random rng, Params p) {
        Node[] cand = feasible(t, nodes);
        if (cand.length == 1) return cand[0];
        boolean crit = t.u >= p.ucrit;
        switch (kind) {
            case FCFS: { // first available server in declaration order, else the one that frees up first
                Node best = null; double bf = Double.POSITIVE_INFINITY;
                for (Node v : cand) { if (v.idle(now, 0)) return v; if (v.freeAt[0] < bf) { bf = v.freeAt[0]; best = v; } }
                return best;
            }
            case RR: return cand[rr++ % cand.length];
            case GREEDY: { Eval e = new Eval(t, cand, now, false, p, true, true); return cand[e.argminResp()]; }
            case ABL_GATECLOUD:
                if (crit) for (Node v : cand) if (v.tier == Tier.CLOUD) return v;
                break;
            default: break;
        }
        if (gate() && crit) { // deadline-feasibility gate: min response among latency-feasible nodes, no search
            Eval e = new Eval(t, cand, now, true, p, useLoad(), useTier());
            int b = -1;
            for (int i = 0; i < e.n; i++) if (e.tresp[i] <= t.D && (b < 0 || e.tresp[i] < e.tresp[b])) b = i;
            return cand[b >= 0 ? b : e.argminResp()];
        }
        Eval e = new Eval(t, cand, now, gate(), p, useLoad(), useTier());
        switch (kind) {
            case PROPOSED_EXACT: return cand[e.argminFit()];
            case PSO: return cand[Swarm.pso(e.fit, p.popSolo, p.itSolo, rng).best];
            case GWO: return cand[Swarm.gwo(e.fit, p.popSolo, p.itSolo, rng).best];
            case WOA: return cand[Swarm.woa(e.fit, Swarm.randomInit(p.popSolo, e.n, rng), p.itSolo, rng, null).best];
            default:  return cand[Swarm.hybrid(e.fit, p, rng).best]; // BASE19, PROPOSED, ABL_*
        }
    }
}
