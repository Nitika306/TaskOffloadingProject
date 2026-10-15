package fogsim;

public final class Task {
    public enum Cls { HEALTH, TRAFFIC, ANALYTICS }

    public final int id;
    public final double t;        // arrival time, s
    public final Cls cls;
    public final double L;        // MI
    public final double din, dout; // Mbit
    public final double mem;      // MB
    public final double bwReq;    // Mbps
    public final double D;        // deadline (relative), s
    public final double u;        // urgency in [0,1]

    public Task(int id, double t, Cls cls, double L, double din, double dout, double mem, double bwReq, double D, double u) {
        this.id = id; this.t = t; this.cls = cls; this.L = L; this.din = din; this.dout = dout;
        this.mem = mem; this.bwReq = bwReq; this.D = D; this.u = u;
    }
}
