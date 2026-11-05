#!/usr/bin/env bash
#
# Starts the minikube cluster and wires Jenkins into it.
#
# The wiring step is the part that is easy to miss. minikube's docker driver
# runs the whole cluster inside a container on its own "minikube" Docker
# network, and its kubeconfig points at that network's address (usually
# 192.168.49.2:8443). The Jenkins container sits on the compose network, so
# until it is also attached to the minikube network, every kubectl call from
# the pipeline times out.

set -Eeuo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JENKINS_CONTAINER="securepipe-jenkins"
MINIKUBE_MEMORY="${MINIKUBE_MEMORY:-4096}"
MINIKUBE_CPUS="${MINIKUBE_CPUS:-2}"

log()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[warn] %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m[fail] %s\033[0m\n' "$*" >&2; exit 1; }

command -v minikube >/dev/null || die "minikube is not installed"
command -v kubectl  >/dev/null || die "kubectl is not installed"

# --- 1. cluster ------------------------------------------------------------
if minikube status --format='{{.Host}}' 2>/dev/null | grep -q Running; then
  log "minikube is already running"
else
  log "Starting minikube (${MINIKUBE_CPUS} cpus, ${MINIKUBE_MEMORY}MB)"
  minikube start \
    --driver=docker \
    --cpus="$MINIKUBE_CPUS" \
    --memory="$MINIKUBE_MEMORY" \
    --addons=metrics-server
fi

minikube status

# --- 2. attach Jenkins to the cluster network -----------------------------
if docker ps --format '{{.Names}}' | grep -qx "$JENKINS_CONTAINER"; then
  log "Attaching Jenkins to the minikube network"
  if docker network inspect minikube --format '{{range .Containers}}{{.Name}} {{end}}' 2>/dev/null \
       | grep -q "$JENKINS_CONTAINER"; then
    echo "already attached"
  else
    docker network connect minikube "$JENKINS_CONTAINER" \
      && echo "attached" \
      || warn "Could not attach. The Helm Deploy stage will fail with a connection timeout."
  fi

  log "Verifying that Jenkins can reach the cluster"
  if docker exec "$JENKINS_CONTAINER" kubectl get nodes 2>&1; then
    printf '\033[1;32m%s\033[0m\n' "Jenkins can talk to Kubernetes."
  else
    warn "kubectl failed from inside Jenkins. Diagnose with:"
    echo "    docker exec $JENKINS_CONTAINER kubectl config view"
    echo "    docker exec $JENKINS_CONTAINER ls -la \$HOME/.minikube"
  fi
else
  warn "The Jenkins container is not running. Start it first:"
  echo "    ./scripts/01-start-toolchain.sh"
fi

cat <<SUMMARY

$(printf '\033[1;32m%s\033[0m' "Cluster is ready.")

Run the pipeline:

  1. Open Jenkins at http://localhost:8080
     (credentials are in ci/.env)
  2. Open the "vrsms-securepipe" job
  3. Click "Build with Parameters", then "Build"

Watch it from the terminal instead:

  ./scripts/03-status.sh

SUMMARY
