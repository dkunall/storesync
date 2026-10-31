#!/usr/bin/env bash
# Starts the compose stack with a database password generated on first use and kept outside the repo (mode 600).
# Reruns reuse it, so the database in an existing volume still accepts it.
set -euo pipefail
cd "$(dirname "$0")/.."
ENV_FILE=${STORESYNC_ENV_FILE:-$HOME/.config/storesync/compose.env}
if [[ ! -f $ENV_FILE ]]; then
  mkdir -p "$(dirname "$ENV_FILE")"
  umask 077
  echo "STORESYNC_DB_PASSWORD=$(openssl rand -hex 24)" > "$ENV_FILE"
  echo "wrote a new database password to $ENV_FILE"
fi
exec docker compose --env-file "$ENV_FILE" up -d --wait --wait-timeout "${WAIT:-300}" "$@"
