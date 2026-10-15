package fogsim;

import java.util.Random;

/** One-dimensional continuous swarm search over the index of the (delay-sorted) candidate list.
 *  A position x in [0, n-1] decodes to node round(x). */
final class Swarm {
    static final class Result { double alpha; int best; double bestFit = Double.POSITIVE_INFINITY; }

    static int idx(double x, int n) { int i = (int) Math.round(x); return i < 0 ? 0 : (i > n - 1 ? n - 1 : i); }
    static double clamp(double x, int n) { return x < 0 ? 0 : (x > n - 1 ? n - 1 : x); }

    /** Grey Wolf Optimiser. Eq. (10). */
    static Result gwo(double[] fit, int pop, int iters, Random r) {
        int n = fit.length; Result res = new Result();
        double[] x = new double[pop];
        for (int i = 0; i < pop; i++) x[i] = r.nextDouble() * (n - 1);
        double[] lp = new double[3]; double[] lf = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        for (int t = 0; t < iters; t++) {
            for (int i = 0; i < pop; i++) {
                int k = idx(x[i], n); double f = fit[k];
                if (f < res.bestFit) { res.bestFit = f; res.best = k; }
                if (f < lf[0]) { lf[2] = lf[1]; lp[2] = lp[1]; lf[1] = lf[0]; lp[1] = lp[0]; lf[0] = f; lp[0] = x[i]; }
                else if (f < lf[1]) { lf[2] = lf[1]; lp[2] = lp[1]; lf[1] = f; lp[1] = x[i]; }
                else if (f < lf[2]) { lf[2] = f; lp[2] = x[i]; }
            }
            double a = 2.0 - 2.0 * t / iters;
            for (int i = 0; i < pop; i++) {
                double s = 0;
                for (int l = 0; l < 3; l++) {
                    double A = 2 * a * r.nextDouble() - a, C = 2 * r.nextDouble();
                    s += lp[l] - A * Math.abs(C * lp[l] - x[i]);
                }
                x[i] = clamp(s / 3.0, n);
            }
        }
        res.alpha = lp[0];
        return res;
    }

    /** Whale Optimisation Algorithm (standard, incl. random-prey branch for |A|>=1). Eqs. (11)-(12).
     *  init = starting positions (random for standalone WOA, seeded around the wolf leader for the hybrid). */
    static Result woa(double[] fit, double[] init, int iters, Random r, Result carry) {
        int n = fit.length, pop = init.length; Result res = carry != null ? carry : new Result();
        double[] x = init.clone();
        double lead = x[0]; double lf = Double.POSITIVE_INFINITY;
        if (carry != null) { lead = carry.alpha; lf = carry.bestFit; }
        for (int t = 0; t < iters; t++) {
            for (int i = 0; i < pop; i++) {
                int k = idx(x[i], n); double f = fit[k];
                if (f < res.bestFit) { res.bestFit = f; res.best = k; }
                if (f < lf) { lf = f; lead = x[i]; }
            }
            double a = 2.0 - 2.0 * t / iters, a2 = -1.0 - (double) t / iters;
            for (int i = 0; i < pop; i++) {
                double A = 2 * a * r.nextDouble() - a, C = 2 * r.nextDouble(), p = r.nextDouble();
                double nx;
                if (p < 0.5) {
                    if (Math.abs(A) < 1) nx = lead - A * Math.abs(C * lead - x[i]);
                    else { double xr = x[r.nextInt(pop)]; nx = xr - A * Math.abs(C * xr - x[i]); }
                } else {
                    double l = (a2 - 1) * r.nextDouble() + 1;
                    nx = Math.abs(lead - x[i]) * Math.exp(l) * Math.cos(2 * Math.PI * l) + lead;
                }
                x[i] = clamp(nx, n);
            }
        }
        res.alpha = lead;
        return res;
    }

    static double[] randomInit(int pop, int n, Random r) {
        double[] x = new double[pop]; for (int i = 0; i < pop; i++) x[i] = r.nextDouble() * (n - 1); return x;
    }

    /** Hybrid of the paper: GWO exploration, then a whale pod seeded within +-1 index of the wolf leader. */
    static Result hybrid(double[] fit, Params p, Random r) {
        Result g = gwo(fit, p.nw, p.ih, r);
        int n = fit.length; double[] seed = new double[p.nh];
        for (int i = 0; i < p.nh; i++) seed[i] = clamp(g.alpha + (r.nextInt(3) - 1), n);
        return woa(fit, seed, p.iw, r, g);
    }

    /** Standard PSO (w=0.7, c1=c2=1.5). */
    static Result pso(double[] fit, int pop, int iters, Random r) {
        int n = fit.length; Result res = new Result();
        double[] x = randomInit(pop, n, r), v = new double[pop], pb = x.clone(), pf = new double[pop];
        java.util.Arrays.fill(pf, Double.POSITIVE_INFINITY);
        double gb = x[0], gf = Double.POSITIVE_INFINITY, vmax = Math.max(1.0, (n - 1) / 2.0);
        for (int t = 0; t < iters; t++) {
            for (int i = 0; i < pop; i++) {
                int k = idx(x[i], n); double f = fit[k];
                if (f < res.bestFit) { res.bestFit = f; res.best = k; }
                if (f < pf[i]) { pf[i] = f; pb[i] = x[i]; }
                if (f < gf) { gf = f; gb = x[i]; }
            }
            for (int i = 0; i < pop; i++) {
                v[i] = 0.7 * v[i] + 1.5 * r.nextDouble() * (pb[i] - x[i]) + 1.5 * r.nextDouble() * (gb - x[i]);
                v[i] = Math.max(-vmax, Math.min(vmax, v[i]));
                x[i] = clamp(x[i] + v[i], n);
            }
        }
        res.alpha = gb;
        return res;
    }
}
