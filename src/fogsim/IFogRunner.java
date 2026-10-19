
package fogsim;

import fogsim.Node.Tier;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.*;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.application.Application;
import org.fog.entities.FogBroker;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacementMapping;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;

public final class IFogRunner {

    static double idlePower(Tier t) {
        return t == Tier.CLOUD ? 16 * 83.25
                : t == Tier.REGIONAL ? 83.4333 : 82.44;
    }

    static FogDevice createDevice(
            String name,
            Tier tier,
            double mips,
            int ramMb,
            double bwMbps,
            double upLatencyUnits,
            double busyW,
            double idleW) {

        List<Pe> pes = new ArrayList<>();
        pes.add(new Pe(0, new PeProvisionerOverbooking(mips)));

        int hostId = FogUtils.generateEntityId();

        PowerHost host = new PowerHost(
                hostId,
                new RamProvisionerSimple(ramMb),
                new BwProvisionerOverbooking(10000),
                1000000L,
                pes,
                new StreamOperatorScheduler(pes),
                new FogLinearPowerModel(busyW, idleW));

        List<Host> hosts = new ArrayList<>();
        hosts.add(host);

        FogDeviceCharacteristics ch = new FogDeviceCharacteristics(
                "x86", "Linux", "Xen", host,
                10.0, 3.0, 0.05, 0.001, 0.0);

        try {
            FogDevice d = new FogDevice(
                    name, ch, new AppModuleAllocationPolicy(hosts),
                    new LinkedList<Storage>(),
                    10, bwMbps, bwMbps, upLatencyUnits, 0.0);

            d.setLevel(tier.ordinal());
            return d;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create FogDevice: " + name, e);
        }
    }

    static FogBroker createBroker() {
        try {
            return new FogBroker("broker");
        } catch (Exception e) {
            throw new RuntimeException("Failed to create FogBroker", e);
        }
    }

    public static Simulator.Result run(
            Node[] proto,
            Task[] tasks,
            Scheduler.Kind kind,
            Params p,
            long seed,
            String topo,
            String wl,
            IFogDispatcher.Queue qm) {

        Node[] nodes = Topology.fresh(proto);
        Scheduler scheduler = new Scheduler(kind);

        if (scheduler.gate()) {
            for (Node v : nodes) {
                if (v.tier == Tier.EDGE) {
                    v.resFrac = p.resFrac;
                }
            }
        }

        Random rng = new Random(seed * 31L + kind.ordinal());

        PrintStream originalOut = System.out;
        IFogDispatcher ctl = null;
        PrintStream suppressedOut = new PrintStream(OutputStream.nullOutputStream());

        try {
            System.setOut(suppressedOut);
            Log.disable();

            CloudSim.init(1, Calendar.getInstance(), false);
            resetTimeKeeper();

            FogBroker broker = createBroker();

            String appId = "offload";
            Application app =
                    Application.createApplication(appId, broker.getId());

            ModuleMapping mapping = ModuleMapping.createModuleMapping();
            List<FogDevice> devices = new ArrayList<>();

            IFogDispatcher.Lane[][] lanes =
                    new IFogDispatcher.Lane[nodes.length][2];

            for (Node v : nodes) {
                int laneCount = v.resFrac > 0 ? 2 : 1;

                for (int l = 0; l < laneCount; l++) {
                    double mips = v.rate(l);

                    if (!(mips > 0) || !Double.isFinite(mips)) {
                        throw new IllegalStateException(
                                "Invalid MIPS for node " + v.id
                                + ", lane " + l + ": " + mips);
                    }

                    String deviceName = "n" + v.id + "_l" + l;
                    String moduleName = "task_" + deviceName;

                    FogDevice device = createDevice(
                            deviceName,
                            v.tier,
                            mips,
                            (int) v.ram,
                            v.bw,
                            v.delta * IFogDispatcher.TS,
                            v.power,
                            idlePower(v.tier));

                    devices.add(device);

                    // This checkout uses the four-argument addAppModule API.
                    app.addAppModule(moduleName, 10, (int) mips, 10);
                    mapping.addModuleToDevice(moduleName, deviceName);

                    lanes[v.id][l] =
                            new IFogDispatcher.Lane(device, moduleName);
                }
            }

            ctl = new IFogDispatcher(
                    devices, nodes, tasks, scheduler, p, rng,
                    lanes, qm, appId, broker.getId());

            ctl.submitApplication(
                    app, 0, new ModulePlacementMapping(devices, app, mapping));

            CloudSim.terminateSimulation(
                    (IFogDispatcher.T0 + Workload.DURATION + 600.0)
                    * IFogDispatcher.TS);

            CloudSim.startSimulation();

        } finally {
            System.setOut(originalOut);
            suppressedOut.close();
        }

        // Print diagnostics before the incomplete-task exception.
        ctl.printDiagnostics();

        if (ctl.completed != tasks.length) {
            throw new IllegalStateException(
                    "iFogSim2 completed " + ctl.completed + " of "
                    + tasks.length + " tasks (scheduler " + kind
                    + ", seed " + seed + "). Inspect the diagnostic report above.");
        }

        return Metrics.summarize(
                ctl.resp,
                ctl.gated,
                ctl.offSum,
                ctl.energy,
                ctl.decNs,
                ctl.tierCnt,
                ctl.lastFinish,
                nodes,
                tasks,
                kind,
                seed,
                topo,
                wl);
    }

    static void resetTimeKeeper() {
        try {
            Class.forName("org.fog.utils.TimeKeeper")
                    .getMethod("deleteInstance")
                    .invoke(null);
        } catch (ReflectiveOperationException ignored) {
            // This iFogSim version may not expose deleteInstance().
        }
    }
}
