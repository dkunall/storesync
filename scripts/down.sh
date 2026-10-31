#!/usr/bin/env bash
# Stops the stack and deletes its volumes. Leaves the env file alone; delete it yourself if you want a new password.
set -euo pipefail
cd "$(dirname "$0")/.."
ENV_FILE=${STORESYNC_ENV_FILE:-$HOME/.config/storesync/compose.env}
exec docker compose --env-file "$ENV_FILE" down -v "$@"
