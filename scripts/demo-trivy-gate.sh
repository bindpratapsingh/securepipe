#!/usr/bin/env bash
#
# Demonstrates the security gate side by side.
#
# Builds the same application twice - once on the hardened Alpine JRE base and
# once on the deprecated openjdk:17-jdk-slim base - and scans both for CRITICAL
# vulnerabilities with the exact command the pipeline uses.
#
# The hardened image should pass and the vulnerable one should fail. That
# difference is the whole point of the Trivy stage: the pipeline stops before
# the Helm deploy rather than shipping a known-vulnerable image.
#
# Trivy runs as a container against the host Docker socket, so nothing needs
# to be installed on the host.

set -Eeuo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$REPO_ROOT/app"
CACHE_VOL="securepipe-trivy-cache"

log()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
pass() { printf '\033[1;32m%s\033[0m\n' "$*"; }
fail() { printf '\033[1;31m%s\033[0m\n' "$*"; }

trivy_scan() {
  # $1 = image ref, $2 = extra flags
  docker run --rm \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "${CACHE_VOL}:/root/.cache" \
    aquasec/trivy:latest image \
      --severity CRITICAL \
      --exit-code 1 \
      --no-progress \
      ${2:-} \
      "$1"
}

# --- build the jar once; both images just copy it in ----------------------
if [ ! -f "$APP_DIR/target/vrsms-server.jar" ]; then
  log "Building the application jar"
  ( cd "$APP_DIR" && ./mvnw -B -ntp clean package -DskipTests )
else
  log "Reusing the existing jar at app/target/vrsms-server.jar"
fi

log "Building both images"
docker build -q -f "$APP_DIR/Dockerfile"            -t vrsms-demo:hardened   "$APP_DIR"
docker build -q -f "$APP_DIR/Dockerfile.vulnerable" -t vrsms-demo:vulnerable "$APP_DIR"

echo
docker images --filter "reference=vrsms-demo" \
  --format 'table {{.Repository}}:{{.Tag}}\t{{.Size}}'

# --- scan 1: the hardened image -------------------------------------------
log "Scanning vrsms-demo:hardened  (Alpine JRE, non-root)"
if trivy_scan vrsms-demo:hardened "--ignore-unfixed"; then
  HARDENED_RESULT=0
  pass "PASSED - no fixable CRITICAL vulnerabilities. The pipeline would deploy this."
else
  HARDENED_RESULT=1
  fail "FAILED - CRITICAL findings even on the hardened base."
  echo "If this happens, a new CVE has landed in the base image since this was written."
  echo "That is normal, and is exactly why the scan runs on every build rather than once."
fi

# --- scan 2: the deliberately vulnerable image ----------------------------
log "Scanning vrsms-demo:vulnerable  (deprecated openjdk:17-jdk-slim, root)"
if trivy_scan vrsms-demo:vulnerable; then
  VULN_RESULT=0
  pass "PASSED - unexpected. Try an older base image in app/Dockerfile.vulnerable."
else
  VULN_RESULT=1
  fail "FAILED - CRITICAL vulnerabilities found. The pipeline would stop here."
fi

# --- summary ---------------------------------------------------------------
cat <<SUMMARY

$(printf '\033[1;36m%s\033[0m' "── What just happened ───────────────────────────")

  vrsms-demo:hardened     exit $HARDENED_RESULT   $([ $HARDENED_RESULT -eq 0 ] && echo "gate open, deploy proceeds" || echo "gate closed")
  vrsms-demo:vulnerable   exit $VULN_RESULT   $([ $VULN_RESULT -eq 1 ] && echo "gate closed, deploy blocked" || echo "gate open")

Trivy's own --exit-code 1 is what fails the stage. No parsing of its output,
no custom thresholds: the scanner reports the verdict and the shell's exit
status propagates it to Jenkins.

To see this inside the pipeline, run the Jenkins job with:

  DOCKERFILE      = Dockerfile.vulnerable
  IGNORE_UNFIXED  = false
  RUN_SONAR       = false        (just to make the run quicker)

The build will stop at "Trivy Scan" and the Helm Deploy stage will never run.

Clean up the demo images:

  docker rmi vrsms-demo:hardened vrsms-demo:vulnerable

SUMMARY
