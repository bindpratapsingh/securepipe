#!/usr/bin/env bash
#
# Stops everything. Worth running at the end of every session: a Codespace
# keeps billing core-hours while it is awake, and the free tier is finite.
#
#   ./scripts/stop.sh          stop containers and the cluster, keep all data
#   ./scripts/stop.sh --purge  also delete the cluster and every volume
#
# Without --purge, the next start reuses the Jenkins home, the Maven cache,
# the Trivy database and the SonarQube history, so it comes back in under a
# minute instead of rebuilding from scratch.

set -Eeuo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PURGE=0
[ "${1:-}" = "--purge" ] && PURGE=1

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }

log "Stopping the toolchain containers"
( cd "$REPO_ROOT/ci" && docker compose stop ) || true

if [ "$PURGE" -eq 1 ]; then
  log "Removing containers and volumes (Jenkins jobs, Sonar history, caches)"
  ( cd "$REPO_ROOT/ci" && docker compose down --volumes --remove-orphans ) || true

  log "Deleting the minikube cluster"
  minikube delete || true

  printf '\n\033[1;33m%s\033[0m\n' "Purged. The next run of 01-start-toolchain.sh rebuilds from scratch (~10 min)."
  echo "ci/.env was left in place. Delete it too if you want fresh credentials."
else
  log "Stopping minikube (the cluster is kept, so it restarts quickly)"
  minikube stop || true

  printf '\n\033[1;32m%s\033[0m\n' "Stopped. Everything is preserved."
  echo "Bring it back with:"
  echo "    ./scripts/01-start-toolchain.sh && ./scripts/02-start-minikube.sh"
fi

if [ -n "${CODESPACE_NAME:-}" ]; then
  cat <<'TIP'

You are in a Codespace, which bills for wall-clock time while it is awake.
Stop the Codespace itself as well:

    gh codespace stop

Or from the browser: the Codespaces menu in the bottom-left, then
"Stop Current Codespace".
TIP
fi
