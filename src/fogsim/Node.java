package fogsim;

/** A fog/edge/cloud node modelled as one or two FIFO servers (main lane + optional reserved lane for urgent tasks).
 *  Parameters mirror an iFogSim2 FogDevice: MIPS, RAM, bandwidth, uplink latency, busy power. */
public final class Node {
    public enum Tier { EDGE, REGIONAL, CLOUD }

    public final int id;
    public final Tier tier;
    public final double mu;      // MIPS
    public final double ram;     // MB
    public final double bw;      // Mbps
    public final double delta;   // one-way uplink delay, seconds
    public final double power;   // busy power, W
    public double resFrac;       // fraction of capacity reserved for urgent tasks (lane 1)

    final double[] freeAt = new double[2];
    public double busyMI;
    public int assigned;

    public Node(int id, Tier tier, double mu, double ram, double bw, double delta, double power) {
        this.id = id; this.tier = tier; this.mu = mu; this.ram = ram; this.bw = bw; this.delta = delta; this.power = power;
    }

    public Node copyWithDelta(int newId, double newDelta) {
        return new Node(newId, tier, mu, ram, bw, newDelta, power);
    }

    public double rate(int lane) { return lane == 1 ? mu * resFrac : mu * (1.0 - resFrac); }

    public boolean idle(double now, int lane) { return freeAt[lane] <= now; }
}
