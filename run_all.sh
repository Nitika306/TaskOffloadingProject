#!/bin/sh
# Build, run the whole study (tune -> main -> WAN sweep -> sensitivity) and analyse. Needs JDK 17+ and Python 3 (numpy, pandas, scipy, matplotlib).
set -e
rm -rf out && mkdir out
javac -d out src/fogsim/*.java
java -cp out fogsim.Main all 30 results
python3 analysis/analyze.py results
