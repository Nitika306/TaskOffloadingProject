package fogsim;

import fogsim.Node.Tier;

/** Topology factory. Node order is the "declaration order": edge, regional, cloud.
 *  Power values follow the iFogSim example applications (cloud 1648 W, fog gateway 107.339 W, edge device 87.53 W busy);
 *  verify against your iFogSim2 copy. */
public final class Topology {
    public static final double WAN_DEFAULT = 0.100; // cloud one-way delay, s

    public static Node[] tiered(double cloudDelta, int scale) {
        Node[] n = new Node[4 * scale + 3 * scale + 1];
        int k = 0;
        for (int i = 0; i < 4 * scale; i++) n[k] = new Node(k++, Tier.EDGE, 4000, 512, 50, 0.002, 87.53);
        for (int i = 0; i < 3 * scale; i++) n[k] = new Node(k++, Tier.REGIONAL, 8000, 4096, 200, 0.015, 107.339);
        n[k] = new Node(k, Tier.CLOUD, 40000 * scale, 65536, 400, cloudDelta, 1648.0);
        return n;
    }

    /** Control topology: identical hardware, every node at the same network distance (cf. the base study [19]). */
    public static Node[] flat(int scale) {
        Node[] t = tiered(0.015, scale);
        Node[] f = new Node[t.length];
        for (int i = 0; i < t.length; i++) f[i] = t[i].copyWithDelta(i, 0.015);
        return f;
    }

    public static Node[] fresh(Node[] proto) {
        Node[] n = new Node[proto.length];
        for (int i = 0; i < n.length; i++) n[i] = proto[i].copyWithDelta(proto[i].id, proto[i].delta);
        return n;
    }
}
