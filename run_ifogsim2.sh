#!/bin/sh
# Usage:  ./run_ifogsim2.sh <mode>
#   build      compile everything (needs IFOGSIM_HOME: a built iFogSim2 checkout)
#   calibrate  cross-check iFogSim2 against the arrival-order FIFO reference on 19 small cases. RUN THIS FIRST.
#   main       tiered+flat, 4 workloads, 13 schedulers, seeds 31-60 on iFogSim2 (FIFO lanes)   -> results_ifog/
#   ps         same for the tiered topology with iFogSim2's native processor-sharing TupleScheduler -> results_ifog_ps/
#   ref        the same runs on the pure-Java arrival-order reference (no iFogSim2 needed)      -> results_ref/
#   compare    ref vs iFogSim2 vs original results
# Environment: IFOGSIM_HOME (required except for 'ref'), JOBS (parallel JVMs, default 4)
set -e
JOBS=${JOBS:-4}
PURE="Params Node Task Topology Workload Scheduler Swarm Eval Simulator Experiments Metrics ReferenceSim RefMain OrderCheck"

build_pure() { rm -rf out_pure && mkdir out_pure; for c in $PURE; do F="$F src/fogsim/$c.java"; done; javac -d out_pure $F; }
build() {
  : "${IFOGSIM_HOME:?Set IFOGSIM_HOME to your iFogSim2 checkout (compile it first, e.g. mvn -q compile)}"
  CP="$(cygpath -w "$IFOGSIM_HOME/out/full-build");$(cygpath -w "$IFOGSIM_HOME/jars")/*"
  rm -rf out && mkdir out && javac -cp "$CP" -d out src/fogsim/*.java
}
cp_run() { echo "$(cygpath -w "$PWD/out");$(cygpath -w "$IFOGSIM_HOME/out/full-build");$(cygpath -w "$IFOGSIM_HOME/jars")/*"; }

case "${1:-calibrate}" in
  build) build ;;
  calibrate) build; java -cp "$(cp_run)" fogsim.IFogMain calibrate --params params/tuned_params.properties ;;
  main)
    build; mkdir -p results_ifog
    for t in tiered flat; do for w in P30 P60 P90 B60; do echo "$t $w"; done; done |
      xargs -P "$JOBS" -L1 sh -c 'java -cp "$0" fogsim.IFogMain run --topo $1 --wl $2 --seeds 31-60 --queue fifo --params params/tuned_params.properties --out results_ifog/fifo_$1_$2.csv' "$(cp_run)"
    head -1 results_ifog/fifo_tiered_P30.csv > results_ifog/main_ifog_fifo.csv
    tail -q -n +2 results_ifog/fifo_*_*.csv >> results_ifog/main_ifog_fifo.csv ;;
  ps)
    build; mkdir -p results_ifog_ps
    for w in P30 P60 P90 B60; do echo "tiered $w"; done |
      xargs -P "$JOBS" -L1 sh -c 'java -cp "$0" fogsim.IFogMain run --topo $1 --wl $2 --seeds 31-60 --queue ps --params params/tuned_params.properties --out results_ifog_ps/ps_$1_$2.csv' "$(cp_run)"
    head -1 results_ifog_ps/ps_tiered_P30.csv > results_ifog_ps/main_ifog_ps.csv
    tail -q -n +2 results_ifog_ps/ps_*_*.csv >> results_ifog_ps/main_ifog_ps.csv ;;
  ref)
    build_pure; mkdir -p results_ref
    for t in tiered flat; do for w in P30 P60 P90 B60; do echo "$t $w"; done; done |
      xargs -P "$JOBS" -L1 sh -c 'java -cp out_pure fogsim.RefMain --topo $0 --wl $1 --seeds 31-60 --params params/tuned_params.properties --out results_ref/ref_$0_$1.csv'
    head -1 results_ref/ref_tiered_P30.csv > results_ref/main_ref.csv
    tail -q -n +2 results_ref/ref_*_*.csv >> results_ref/main_ref.csv ;;
  compare)
    "/c/Users/DELL/AppData/Local/Programs/Python/Python310/python.exe" compare_with_custom.py reference/main.csv results_ref/main_ref.csv "original (decision-order lanes)" "reference (arrival-order FIFO)"
    "/c/Users/DELL/AppData/Local/Programs/Python/Python310/python.exe" compare_with_custom.py results_ref/main_ref.csv results_ifog/main_ifog_fifo.csv "reference (arrival-order FIFO)" "iFogSim2 (FIFO lanes)" ;;
  *) echo "unknown mode"; exit 1 ;;
esac
