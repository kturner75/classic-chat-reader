#!/usr/bin/env bash
set -euo pipefail
ROOT=/Users/kevinturner/IdeaProjects/classic-chat-reader
EV=/tmp/ccr-pr174-97b460e
cd "$ROOT"
mkdir -p "$EV/evidence"
pkill -f 'com.classicchatreader.ReaderApplication' 2>/dev/null || true
pkill -f 'multiModuleProjectDirectory=/Users/kevinturner/IdeaProjects/classic-chat-reader' 2>/dev/null || true
sleep 2
git checkout --force 97b460e8e1bd3f688468da972dad1d0397a8083f
git rev-parse HEAD | tee "$EV/pinned-head.txt"
mvn -q -DskipTests process-resources compile
: > "$EV/server.log"
nohup bash scripts/start_local.sh >> "$EV/server.log" 2>&1 &
echo $! > "$EV/server.pid"
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/ || true)
  if [ "$code" = "200" ]; then echo HEALTH_OK; break; fi
  sleep 2
done
git checkout --force 97b460e8e1bd3f688468da972dad1d0397a8083f >/dev/null
GIT_HASH=$(git show 97b460e8e1bd3f688468da972dad1d0397a8083f:src/main/resources/static/js/sensitive-request-guard.js | shasum -a 256 | awk '{print $1}')
LIVE_HASH=$(curl -s http://127.0.0.1:8080/js/sensitive-request-guard.js | shasum -a 256 | awk '{print $1}')
echo "pre_fp git=$GIT_HASH live=$LIVE_HASH"
if [ "$LIVE_HASH" != "$GIT_HASH" ]; then
  echo "restarting for fingerprint"
  pkill -f 'com.classicchatreader.ReaderApplication' 2>/dev/null || true
  sleep 2
  mvn -q -DskipTests process-resources
  nohup bash scripts/start_local.sh >> "$EV/server.log" 2>&1 &
  echo $! > "$EV/server.pid"
  for i in $(seq 1 60); do
    code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/ || true)
    if [ "$code" = "200" ]; then break; fi
    sleep 2
  done
fi
cd "$ROOT" && node "$ROOT/.gate-ui-pr174.mjs"
