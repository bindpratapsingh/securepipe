#!/usr/bin/env bash
#
# Brings up the SecurePipe toolchain: SonarQube, then Jenkins.
#
# The order matters. Jenkins reads its SonarQube API token at boot through
# Configuration as Code, so the token has to exist before Jenkins starts.
# This script therefore:
#
#   1. raises vm.max_map_count, which SonarQube's embedded Elasticsearch needs
#   2. starts SonarQube and waits for it to report UP
#   3. changes the default admin password (SonarQube blocks API use until then)
#   4. mints an API token for Jenkins
#   5. registers a webhook so SonarQube can call the quality gate back
#   6. writes all of that to ci/.env, then builds and starts Jenkins
#
# Safe to re-run. It reuses the credentials already in ci/.env.

set -Eeuo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CI_DIR="$REPO_ROOT/ci"
ENV_FILE="$CI_DIR/.env"
SONAR_URL="http://localhost:9000"

log()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[warn] %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m[fail] %s\033[0m\n' "$*" >&2; exit 1; }

cd "$CI_DIR"

# --- 0. preflight ----------------------------------------------------------
command -v docker >/dev/null || die "docker is not installed"
docker info >/dev/null 2>&1 || die "the docker daemon is not reachable"

# --- 1. kernel setting for SonarQube's embedded Elasticsearch ---------------
log "Setting vm.max_map_count for SonarQube"
CURRENT_MMC="$(cat /proc/sys/vm/max_map_count 2>/dev/null || echo 0)"
if [ "$CURRENT_MMC" -lt 262144 ]; then
  sudo sysctl -w vm.max_map_count=262144 \
    || warn "Could not raise vm.max_map_count. SONAR_ES_BOOTSTRAP_CHECKS_DISABLE should cover it."
else
  echo "already $CURRENT_MMC, nothing to do"
fi

# --- 2. load or create credentials -----------------------------------------
if [ -f "$ENV_FILE" ]; then
  log "Reusing credentials from ci/.env"
  # shellcheck disable=SC1090
  set -a; . "$ENV_FILE"; set +a
else
  log "Generating credentials into ci/.env"
  JENKINS_ADMIN_ID="admin"
  JENKINS_ADMIN_PASSWORD="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 16)"
  SONAR_ADMIN_PASSWORD="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 16)"
  SONAR_TOKEN=""
fi

# In a Codespace the forwarded hostname is predictable, so Jenkins can be told
# its real external URL. That only affects the links Jenkins prints.
if [ -n "${CODESPACE_NAME:-}" ]; then
  JENKINS_URL="https://${CODESPACE_NAME}-8080.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:-app.github.dev}/"
else
  JENKINS_URL="http://localhost:8080/"
fi

# --- 3. start SonarQube ----------------------------------------------------
log "Starting SonarQube"
docker compose up -d sonarqube

log "Waiting for SonarQube to report UP (first boot takes 2-4 minutes)"
for i in $(seq 1 120); do
  STATUS="$(curl -fsS "$SONAR_URL/api/system/status" 2>/dev/null \
            | sed -n 's/.*"status":"\([A-Z]*\)".*/\1/p' || true)"
  if [ "$STATUS" = "UP" ]; then
    echo "SonarQube is UP after ~$((i * 5))s"
    break
  fi
  [ $((i % 6)) -eq 0 ] && echo "  still ${STATUS:-starting}... ($((i * 5))s)"
  sleep 5
  [ "$i" -eq 120 ] && die "SonarQube did not come up in 10 minutes. Try: docker compose logs sonarqube"
done

# --- 4. password + token ---------------------------------------------------
# A fresh SonarQube refuses most API calls until admin/admin is replaced.
log "Configuring the SonarQube admin account"
if curl -fsS -u "admin:admin" "$SONAR_URL/api/authentication/validate" 2>/dev/null | grep -q '"valid":true'; then
  echo "Default password is still in place; changing it"
  curl -fsS -u "admin:admin" -X POST \
    --data-urlencode "login=admin" \
    --data-urlencode "previousPassword=admin" \
    --data-urlencode "password=${SONAR_ADMIN_PASSWORD}" \
    "$SONAR_URL/api/users/change_password" >/dev/null \
    || die "Could not change the SonarQube admin password"
  echo "done"
else
  echo "Default password already changed; using the one in ci/.env"
  curl -fsS -u "admin:${SONAR_ADMIN_PASSWORD}" "$SONAR_URL/api/authentication/validate" 2>/dev/null \
    | grep -q '"valid":true' \
    || die "Cannot authenticate to SonarQube. Delete ci/.env and the sonarqube_data volume to start clean."
fi

SONAR_AUTH="admin:${SONAR_ADMIN_PASSWORD}"

log "Minting an API token for Jenkins"
# Revoke first: generate fails if a token of this name already exists, and the
# secret itself is only ever shown once, so it cannot be read back.
curl -fsS -u "$SONAR_AUTH" -X POST \
  --data-urlencode "name=jenkins" \
  "$SONAR_URL/api/user_tokens/revoke" >/dev/null 2>&1 || true

SONAR_TOKEN="$(curl -fsS -u "$SONAR_AUTH" -X POST \
  --data-urlencode "name=jenkins" \
  "$SONAR_URL/api/user_tokens/generate" \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"

[ -n "$SONAR_TOKEN" ] || die "Failed to mint a SonarQube token"
echo "token minted (${SONAR_TOKEN:0:8}...)"

# --- 5. quality gate webhook ----------------------------------------------
# waitForQualityGate in the Jenkinsfile parks the build until SonarQube posts
# the gate result here. Both containers share a compose network, so Jenkins is
# reachable as the hostname "jenkins".
log "Registering the quality gate webhook"
WEBHOOK_URL="http://jenkins:8080/sonarqube-webhook/"
if curl -fsS -u "$SONAR_AUTH" "$SONAR_URL/api/webhooks/list" | grep -q "$WEBHOOK_URL"; then
  echo "webhook already registered"
else
  curl -fsS -u "$SONAR_AUTH" -X POST \
    --data-urlencode "name=jenkins" \
    --data-urlencode "url=${WEBHOOK_URL}" \
    "$SONAR_URL/api/webhooks/create" >/dev/null \
    && echo "webhook created -> $WEBHOOK_URL" \
    || warn "Webhook creation failed. The Quality Gate stage will time out and mark the build unstable."
fi

# --- 6. persist and start Jenkins -----------------------------------------
log "Writing ci/.env"
umask 077
cat > "$ENV_FILE" <<ENVEOF
# Generated by scripts/01-start-toolchain.sh. Gitignored - do not commit.
JENKINS_ADMIN_ID=${JENKINS_ADMIN_ID}
JENKINS_ADMIN_PASSWORD=${JENKINS_ADMIN_PASSWORD}
JENKINS_URL=${JENKINS_URL}
SONAR_ADMIN_PASSWORD=${SONAR_ADMIN_PASSWORD}
SONAR_TOKEN=${SONAR_TOKEN}
ENVEOF
umask 022

# These are bind-mounted into the Jenkins container. If they do not exist yet,
# Docker creates them as root-owned, and minikube then cannot write its
# kubeconfig into them.
mkdir -p "$HOME/.kube" "$HOME/.minikube"

log "Building and starting Jenkins (the first build installs plugins, ~3-5 min)"
docker compose up -d --build jenkins

log "Waiting for Jenkins to accept requests"
for i in $(seq 1 60); do
  if curl -fsS -o /dev/null "http://localhost:8080/login" 2>/dev/null; then
    echo "Jenkins is up after ~$((i * 5))s"
    break
  fi
  sleep 5
  [ "$i" -eq 60 ] && warn "Jenkins is slow to start. Check: docker compose logs -f jenkins"
done

cat <<SUMMARY

$(printf '\033[1;32m%s\033[0m' "Toolchain is running.")

  Jenkins     http://localhost:8080
              user: ${JENKINS_ADMIN_ID}
              pass: ${JENKINS_ADMIN_PASSWORD}

  SonarQube   http://localhost:9000
              user: admin
              pass: ${SONAR_ADMIN_PASSWORD}

  Credentials are stored in ci/.env

Next: start the cluster, then run the pipeline.

  ./scripts/02-start-minikube.sh

SUMMARY
