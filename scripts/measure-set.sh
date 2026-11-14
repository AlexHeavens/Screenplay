#!/usr/bin/env bash
# usage: run-set.sh label "serials" ; runs the set with sampler, prints summary
label=$1; serials=$2; d=${RESULTS_DIR:-/tmp/pdfaudiobook-measure}; mkdir -p $d
scripts/measure-sampler.sh $d/$label.samples & sp=$!
{ echo "uptime before: $(uptime)"; free -m; } > $d/$label.env
POOL_SERIALS="$serials" scripts/ui-tests-pool.sh BookUiTest ImportQueueUiTest FindBooksUiTest > $d/$label.out 2>&1
{ echo "uptime after: $(uptime)"; free -m; } >> $d/$label.env
kill $sp
