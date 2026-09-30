#!/usr/bin/env bash
# Deploys the City Walk API (server/api) to the home server with pm2.
# Run from the repo root in Git Bash, on the home network (needs SSH key access).
# The upload token comes from citywalk.token in local.properties (not committed).
set -euo pipefail
SERVER=deploy@192.168.2.41
TOKEN=$(grep '^citywalk.token=' local.properties | cut -d= -f2- | tr -d '\r')
[ -n "$TOKEN" ] || { echo "Set citywalk.token=... in local.properties first" >&2; exit 1; }

ssh "$SERVER" 'mkdir -p ~/citywalk-api'
scp -q server/api/citywalk-api.mjs "$SERVER:citywalk-api/"
printf 'CITYWALK_TOKEN=%s\n' "$TOKEN" | ssh "$SERVER" 'umask 077; cat > ~/citywalk-api/.env'
ssh "$SERVER" 'cd ~/citywalk-api \
  && (pm2 restart citywalk-api >/dev/null 2>&1 \
      || pm2 start citywalk-api.mjs --name citywalk-api --node-args="--env-file=.env" >/dev/null) \
  && pm2 save >/dev/null \
  && sleep 1 && curl -fsS http://127.0.0.1:4323/citywalk/api/health && echo " citywalk-api is running"'
