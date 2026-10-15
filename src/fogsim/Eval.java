package fogsim;

import fogsim.Node.Tier;

/** Per-task evaluation of every candidate node: Eqs. (1)-(9) of the paper. */
final class Eval {
    static final double PTX = 1.0; // transmission power, W
    // tier affinity kappa[class][tier]: natural layer of each task class
    static final double[][] KAPPA = {
        /* HEALTH    */ {1.0, 0.6, 0.1},
        /* TRAFFIC   */ {0.4, 1.0, 0.4},
        /* ANALYTICS */ {0.1, 0.6, 1.0}
    };

    final int n; final Node[] cand; final int[] lane;
    final double[] toff, texe, tque, tresp, energy, fit;

    Eval(Task t, Node[] cand, double now, boolean gateLanes, Params p, boolean useLoad, boolean useTier) {
        this.n = cand.length; this.cand = cand;
        lane = new int[n]; toff = new double[n]; texe = new double[n]; tque = new double[n];
        tresp = new double[n]; energy = new double[n]; fit = new double[n];
        boolean crit = t.u >= p.ucrit;
        double tref = 1e-9, eref = 1e-9;
        for (int i = 0; i < n; i++) {
            Node v = cand[i];
            lane[i] = (gateLanes && crit && v.resFrac > 0) ? 1 : 0;
            toff[i] = 2 * v.delta + (t.din + t.dout) / v.bw;                 // Eq. (1)
            texe[i] = t.L / v.rate(lane[i]);                                  // Eq. (2)
            tque[i] = Math.max(0.0, v.freeAt[lane[i]] - now);                 // Eq. (2): q_i(t)/mu_i as remaining busy time
            tresp[i] = toff[i] + tque[i] + texe[i];                           // Eq. (3)
            energy[i] = v.power * texe[i] + PTX * (t.din + t.dout) / v.bw;    // Eq. (4)
            tref = Math.max(tref, tresp[i]); eref = Math.max(eref, energy[i]);
        }
        for (int i = 0; i < n; i++) {
            double lat = (p.w1 * toff[i] + p.w2 * tresp[i] + p.w3 * texe[i]) / tref + p.w4 * energy[i] / eref; // Eq. (6)
            double phi = (p.am * Math.max(0, tresp[i] - t.D) - p.ar * Math.max(0, t.D - tresp[i])) / t.D;      // Eq. (8)
            double rho = (tque[i] + texe[i]) / p.H;                                                           // Eq. (9)
            double kappa = KAPPA[t.cls.ordinal()][cand[i].tier.ordinal()];
            fit[i] = lat + phi + (useLoad ? p.w5 * rho : 0.0) - (useTier ? p.om * kappa : 0.0);                // Eq. (7)
        }
    }

    int argminFit() { int b = 0; for (int i = 1; i < n; i++) if (fit[i] < fit[b]) b = i; return b; }
    int argminResp() { int b = 0; for (int i = 1; i < n; i++) if (tresp[i] < tresp[b]) b = i; return b; }
}
