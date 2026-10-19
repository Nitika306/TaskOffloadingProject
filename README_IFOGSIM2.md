# fogsim on iFogSim2

An iFogSim2 implementation of the deadline-gated GWO-WOA study. The 13 schedulers, the fitness (Eqs. 1-9), the swarms, the
workloads and the metrics are your existing classes, **unchanged** (`Scheduler, Swarm, Eval, Node, Task, Topology, Workload, Params`).
What is new is the layer that executes the decisions on iFogSim2.

## Status: read this first

| Part | Verified how |
|---|---|
| Scheduler logic, workloads, metrics | Reproduces your `main.csv` values exactly (e.g. GREEDY B60 critical rate 0.7475, PROPOSED 0.9644). |
| Dispatcher logic (time scaling, FIFO release, response-time maths) | Compiled and run against a **mock event engine** (not iFogSim2). It matches the pure-Java arrival-order reference to rounding error on 19 cases. |
| **Calls into the iFogSim2 API** | **Not tested.** No network access here, so I could not fetch iFogSim2 or compile against it. The calls follow iFogSim2's own example code (VRGame, `Sensor.emitTuple`, `Controller`) as I know it. Expect to fix a signature or two. |

So: build it, run `./run_ifogsim2.sh calibrate`, and do not trust any iFogSim2 number until that prints `CALIBRATION PASSED`.

## Important finding: your lane service order (affects the existing results too)

`Simulator.run` serves each lane in the order tasks were **decided**, not the order they **reach the node**
(`start = max(arrive, freeAt)` with `freeAt` advanced at decision time). A task still in transit, for instance a 400 ms analytics
transfer, therefore blocks a later task that has already arrived and found the node idle. The paper (Section VI.A) says the
response is measured on per-node FIFO lanes, which a reader will take to mean arrival order.

`ReferenceSim` keeps your decisions identical but serves lanes in true arrival order. Effect on seeds 31-60, tiered:

| B60 critical rate | original | arrival-order FIFO |
|---|---|---|
| FCFS / RR / GREEDY | 0.482 / 0.323 / 0.747 | 0.492 / 0.371 / 0.764 |
| PSO / GWO / WOA | 0.993 / 0.992 / 0.993 | 0.994 / 0.993 / 0.993 |
| BASE19 / ABL_NOGATE | 0.978 / 0.993 | 0.980 / 0.993 |
| PROPOSED / PROPOSED_EXACT | 0.964 / 0.965 | 0.964 / 0.965 |

No ranking and no conclusion changes, because PROPOSED's urgent lane is isolated from analytics traffic. But the paper must describe
what the code does, and the iFogSim2 port implements true FIFO. Two clean options:
1. **Recommended:** in `Experiments.java` replace `Simulator.run(` by `ReferenceSim.run(` (same signature), rerun the study and
   analysis, update the tables. Strictly, also rerun tuning, since the eight weights were tuned under the old order (PROPOSED barely
   moves, so I expect no change, but you should check).
2. Keep the original and state "each lane serves tasks in decision order" in Section VI.A.

`java -cp out_pure fogsim.OrderCheck` reproduces the table above for any workload (pure Java, no iFogSim2).

## Build and run

1. Get iFogSim2 (github.com/Cloudslab/iFogSim) and build it: `mvn -q compile` (or compile `src` with the bundled `jars/`).
   Use JDK 17 or whatever it builds with. `export IFOGSIM_HOME=/path/to/iFogSim`. The script assumes the classpath
   `$IFOGSIM_HOME/target/classes` plus `$IFOGSIM_HOME/jars/*`; edit `build()` / `cp_run()` at the top of `run_ifogsim2.sh` if your checkout differs.
2. `./run_ifogsim2.sh calibrate`, which compiles everything and cross-checks iFogSim2 against the reference. **Must pass.**
3. `./run_ifogsim2.sh ref` (pure Java reference), then `./run_ifogsim2.sh main` (iFogSim2, FIFO lanes), then
   `./run_ifogsim2.sh compare`. Optional: `./run_ifogsim2.sh ps` (native processor-sharing sensitivity run).
   One JVM per (topology, workload); `JOBS=4` parallel JVMs. Expect 1-2 CPU-hours for `main`.
4. Outputs have the same columns as `main.csv`, so `analysis/analyze.py` works on them unchanged.

## How the study maps onto iFogSim2

| Study | iFogSim2 |
|---|---|
| Node v_i (Table II) | one `FogDevice` per capacity lane (lane 0 normal; lane 1 = reserved urgent lane on edge nodes when the gate is on), same MIPS/RAM/BW/busy power as Table II, `FogLinearPowerModel` with iFogSim2's example idle powers |
| Task tau_j | `Tuple` (subclass `IFogTaskTuple`), length = L x 1000 |
| Execution of a task | iFogSim2's own VM/cloudlet engine on the lane's `AppModule` |
| Per-task online decision | `IFogDispatcher extends Controller`; calls `Scheduler.assign` at each arrival |
| Completion time | captured in `Cloudlet.setCloudletStatus(SUCCESS)` of the tuple |

Why one device per lane: iFogSim2 gives the active module of a device the **whole host's MIPS**, so two modules on one device
cannot represent an eta / (1-eta) capacity split.

Modelling decisions you must state in the paper:
- **Clock unit = milliseconds.** iFogSim2's internals (event spacing, periodic events) assume ms; the study is in seconds, so delays
  and task lengths are scaled by 1000.
- **Network:** a task is delivered straight to the chosen device after `delta + d_in/B` (Eq. 1); iFogSim2's hierarchical link queue is
  bypassed. The application model is degenerate (one module per lane, no DAG).
- **Queue discipline:** FIFO admission is done in the dispatcher (one task in service per lane) because iFogSim2's native
  `TupleScheduler` is processor-sharing. `--queue ps` uses the native one and is a legitimate sensitivity analysis.
- **Energy:** the paper's formula (busy compute + transmission). iFogSim2's native energy counter is not used: it only updates at
  device events, and idle power would dominate. Idle power is now available if you want to extend the metric.
- **Decision time** is wall-clock around `Scheduler.assign`, as before.

## If calibrate fails or the build breaks

Check these, in this order of likelihood. Each is marked `VERIFY` in the code.
1. Constructor/method signatures: `Tuple` (10 args), `Application.addAppModule(name, ram, mips, size, bw)`,
   `ModuleMapping.addModuleToDevice(module, device)`, `Controller.submitApplication(app, delay, placement)`,
   `FogDevice` constructor, `PeProvisionerOverbooking` / `BwProvisionerOverbooking` package.
2. `IllegalStateException: iFogSim2 completed k of n tasks`: tuples are dropped before a module exists or the module name does not
   match. Print `appToModulesMap` of a device; the first dispatch happens at t = 1 s, after the t = 0 module launch.
3. Small systematic gaps (tens of microseconds per task, accumulating along a busy lane): iFogSim2 does not detect completions
   at the exact instant (minimum spacing between processing updates, 0.11 time units). Acceptable if `dMean` stays under the 0.5 ms
   tolerance; report it. Gaps of several ms or more mean the time unit is wrong: check `CloudSim.init` and the 1000x scaling.
4. `System.exit` in the middle of a run: some iFogSim2 versions exit from `Controller` on `STOP_SIMULATION`; the dispatcher swallows
   that event, so a version with a different event name needs the same treatment.

## Suggested text for Section VI.A (fill the brackets only after the runs)

> The scheduler is implemented on iFogSim2 [30] by extending its Controller with an online per-task dispatcher. Each node of
> Table II is a FogDevice (one per capacity lane) hosting one AppModule; each task is a Tuple delivered to the chosen device after
> the uplink and input-transfer delay and executed by iFogSim2's cloudlet engine, with the simulation clock interpreted as
> milliseconds. Each lane serves one task at a time in order of arrival at the node. Power values are those of the iFogSim2 example
> applications. A cross-check against a standalone Java implementation of the same model on [N] scenarios agreed within [X] ms on
> mean response time and [Y] percentage points on deadline rates. Network links are modelled as fixed delays, not through iFogSim2's
> link queue, and the application graph is a single module per lane.

Then delete the "not the iFogSim2 jar" sentence and the two threats-to-validity items that say the same.

## What this does not change

Porting to iFogSim2 answers the "custom simulator" objection. It does not change the framing, the 8-node scale, the untuned
eta / u_crit, or the "not significant is not equivalent" statistics. Those still need the changes discussed earlier.
