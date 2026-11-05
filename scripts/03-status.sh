#!/usr/bin/env bash
#
# One-screen view of everything SecurePipe is running: the toolchain
# containers, the cluster, the deployed release, and the URLs plus
# credentials you need. Read-only.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$REPO_ROOT/ci/.env"
NAMESPACE="vrsms"

hdr() { printf '\n\033[1;36m── %s ─────────────────────────────\033[0m\n' "$*"; }

hdr "Toolchain containers"
docker ps --filter "name=securepipe-" \
  --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}' 2>/dev/null \
  || echo "docker is not reachable"

hdr "Memory use"
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}' 2>/dev/null \
  | head -20 || true
echo
free -h 2>/dev/null | head -2 || true

hdr "Cluster"
if minikube status --format='{{.Host}}' 2>/dev/null | grep -q Running; then
  kubectl get nodes 2>/dev/null
  echo
  echo "Workloads in the '$NAMESPACE' namespace:"
  kubectl get all --namespace "$NAMESPACE" 2>/dev/null \
    || echo "  nothing deployed yet"
else
  echo "minikube is not running. Start it with ./scripts/02-start-minikube.sh"
fi

hdr "Helm releases"
helm list --all-namespaces 2>/dev/null || echo "helm is not available"

hdr "URLs and credentials"
if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  printf '  Jenkins    http://localhost:8080   %s / %s\n' \
    "${JENKINS_ADMIN_ID:-admin}" "${JENKINS_ADMIN_PASSWORD:-?}"
  printf '  SonarQube  http://localhost:9000   admin / %s\n' \
    "${SONAR_ADMIN_PASSWORD:-?}"
else
  echo "  ci/.env not found. Run ./scripts/01-start-toolchain.sh first."
fi

if kubectl get svc vrsms --namespace "$NAMESPACE" >/dev/null 2>&1; then
  echo
  echo "  Reach the deployed app with:"
  echo "    kubectl -n $NAMESPACE port-forward svc/vrsms 8081:8080"
  echo "    curl http://localhost:8081/actuator/health"
fi
echo
