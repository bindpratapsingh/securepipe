#!/usr/bin/env bash
#
# Checks that the environment can actually run this project, and prints what
# to do next. Runs automatically when the Codespace is created, and is safe to
# run again any time.
#
# It never changes anything, so a failure here is a diagnosis, not damage.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROBLEMS=0

hdr()  { printf '\n\033[1;36m── %s ─────────────────────────────\033[0m\n' "$*"; }
ok()   { printf '  \033[1;32mok\033[0m    %s\n' "$*"; }
bad()  { printf '  \033[1;31mMISSING\033[0m %s\n' "$*"; PROBLEMS=$((PROBLEMS + 1)); }
note() { printf '  \033[1;33mnote\033[0m  %s\n' "$*"; }

check() {
  # $1 = command, $2 = how to read its version
  if command -v "$1" >/dev/null 2>&1; then
    ok "$(printf '%-9s %s' "$1" "$(eval "$2" 2>&1 | head -1)")"
  else
    bad "$1"
  fi
}

hdr "Tooling"
check docker   'docker --version'
check java     'java -version'
check kubectl  'kubectl version --client 2>/dev/null | head -1'
check helm     'helm version --short'
check minikube 'minikube version --short'
check curl     'curl --version'
check git      'git --version'

hdr "Docker daemon"
if docker info >/dev/null 2>&1; then
  ok "reachable"
else
  bad "the docker daemon is not responding (try: sudo service docker start)"
fi

hdr "Resources"
TOTAL_MB="$(free -m 2>/dev/null | awk '/^Mem:/{print $2}')"
CPUS="$(nproc 2>/dev/null || echo '?')"
DISK_AVAIL="$(df -h / 2>/dev/null | awk 'NR==2{print $4}')"
printf '  cpus      %s\n'  "$CPUS"
printf '  memory    %s MB\n' "${TOTAL_MB:-?}"
printf '  disk free %s\n'  "${DISK_AVAIL:-?}"

if [ -n "${TOTAL_MB:-}" ] && [ "$TOTAL_MB" -lt 10000 ]; then
  note "Under 10 GB of RAM. Jenkins + SonarQube + minikube + Maven peak near"
  note "7.5 GB. If builds get killed, lower MINIKUBE_MEMORY or set RUN_SONAR=false."
fi

hdr "Kernel setting for SonarQube"
MMC="$(cat /proc/sys/vm/max_map_count 2>/dev/null || echo 0)"
if [ "$MMC" -ge 262144 ]; then
  ok "vm.max_map_count = $MMC"
else
  note "vm.max_map_count is $MMC; 01-start-toolchain.sh raises it to 262144"
fi

hdr "Project files"
for f in Jenkinsfile ci/docker-compose.yml ci/jenkins/Dockerfile \
         charts/vrsms/Chart.yaml app/pom.xml app/mvnw; do
  if [ -f "$REPO_ROOT/$f" ]; then ok "$f"; else bad "$f"; fi
done

if [ -x "$REPO_ROOT/app/mvnw" ]; then
  ok "app/mvnw is executable"
else
  note "app/mvnw is not executable (fix: chmod +x app/mvnw)"
fi

hdr "Result"
if [ "$PROBLEMS" -eq 0 ]; then
  printf '  \033[1;32mEverything needed is present.\033[0m\n\n'
  cat <<'NEXT'
  Start here:

    ./scripts/01-start-toolchain.sh    Jenkins + SonarQube        (~10 min first time)
    ./scripts/02-start-minikube.sh     the Kubernetes cluster     (~3 min)
    ./scripts/03-status.sh             URLs, credentials, health

  Then open Jenkins and run the "vrsms-securepipe" job.
NEXT
else
  printf '  \033[1;31m%s problem(s) found - see MISSING above.\033[0m\n' "$PROBLEMS"
  echo "  In a Codespace this usually means the devcontainer features are still"
  echo "  installing. Wait for the setup to finish, then run this again."
fi
echo
