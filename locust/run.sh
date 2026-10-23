#!/bin/sh
# Usage: ./run.sh [SCENARIO...]  (default: all three, each with the cache off then on).
# Needs: compose localstack up + terraform applied (envs/local). Output: ../results/locust/<SCENARIO>-cache-<off|on>-<date>-*
cd "$(dirname "$0")"
for S in ${@:-OPTIMISTIC MID PESSIMISTIC}; do
 for C in off on; do
  echo "== $S cache=$C =="
  for Q in us-fanout us-fanout-dlq; do docker exec infra-localstack-1 awslocal sqs purge-queue --queue-url http://localhost:4566/000000000000/$Q >/dev/null 2>&1; done  # no backlog from earlier runs
  CE=false; [ $C = on ] && CE=true
  (cd ../infra && SCENARIO=$S CACHE_ENABLED=$CE SIM_LATENCY_MS=300 docker compose up -d --force-recreate backend >/dev/null 2>&1)
  until curl -sf -m 3 localhost:8080/health >/dev/null; do sleep 1; done
  curl -s -m 30 localhost:8080/metrics >/dev/null  # warm-up: first call after a container restart is slow
  D=$(date +%F); P=../results/locust/$S-cache-$C-$D
  .venv/bin/locust -f locustfile.py --headless -H http://localhost:8080 -u 11 -r 11 -t 45s --csv $P --html $P.html >/dev/null 2>&1
  .venv/bin/python report.py $P-offers.json
 done
done
