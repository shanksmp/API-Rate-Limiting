#!/usr/bin/env bash
# Generates a .env with strong random secrets for docker compose. Won't overwrite an existing one.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .env ]]; then
  echo ".env already exists, leaving it alone"
  exit 0
fi
rand() { openssl rand -base64 48 | tr -d '/+=\n' | cut -c1-"$1"; }
umask 077
cat > .env <<ENV
REDIS_PASSWORD=$(rand 40)
ADMIN_TOKEN=$(rand 48)
API_KEY_PEPPER=$(rand 48)
GRAFANA_ADMIN_PASSWORD=$(rand 24)
APP_REPLICAS=3
ENV
echo "Wrote .env (mode 600). Admin token and Grafana password are in there."
