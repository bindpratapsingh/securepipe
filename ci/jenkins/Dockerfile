# syntax=docker/dockerfile:1
#
# Jenkins controller with the full SecurePipe toolchain baked in.
#
# The pipeline runs every stage on the controller itself. That is not how you
# would build a production Jenkins (you would use agents), but it keeps this
# project to a single container, which matters on constrained hardware.
#
# Tools installed here, and why each one is needed:
#   docker CLI  - build and tag the application image
#   trivy       - scan that image for CVEs, and fail the build on CRITICAL
#   kubectl     - talk to the minikube cluster
#   helm        - render and apply the chart
#   minikube    - "minikube image load", to push the locally built image
#                 into the cluster's own container runtime
#   unzip       - the Maven wrapper is distributionType=only-script, so it
#                 downloads and unzips Maven 3.9.14 on first use
#
# Maven itself is deliberately NOT installed. The repo ships ./mvnw, which
# pins Maven 3.9.14 - Spring Boot 4 requires 3.9+, and Debian's apt only
# carries 3.8.x, which would fail the build.

FROM jenkins/jenkins:lts-jdk17

USER root

# --- base utilities + Docker CLI (client only; it talks to the host daemon
# --- over the mounted socket, so no daemon is installed here) ---
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl gnupg unzip git jq \
 && install -m 0755 -d /etc/apt/keyrings \
 && curl -fsSL https://download.docker.com/linux/debian/gpg \
      -o /etc/apt/keyrings/docker.asc \
 && chmod a+r /etc/apt/keyrings/docker.asc \
 && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
      > /etc/apt/sources.list.d/docker.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends docker-ce-cli docker-buildx-plugin \
 && rm -rf /var/lib/apt/lists/*

# --- Trivy (image vulnerability scanner) ---
# The install script ships only the binary, so the HTML report template is
# fetched separately. The pipeline renders it with --template @<that path>.
RUN curl -fsSL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh \
      | sh -s -- -b /usr/local/bin \
 && trivy --version \
 && mkdir -p /usr/local/share/trivy/templates \
 && curl -fsSLo /usr/local/share/trivy/templates/html.tpl \
      https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/html.tpl

# --- kubectl, pinned to the current stable release at build time ---
RUN KVER="$(curl -fsSL https://dl.k8s.io/release/stable.txt)" \
 && curl -fsSLo /usr/local/bin/kubectl \
      "https://dl.k8s.io/release/${KVER}/bin/linux/$(dpkg --print-architecture)/kubectl" \
 && chmod +x /usr/local/bin/kubectl \
 && kubectl version --client

# --- Helm ---
RUN curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 \
      | bash \
 && helm version

# --- minikube CLI (used only for "minikube image load") ---
RUN curl -fsSLo /usr/local/bin/minikube \
      "https://storage.googleapis.com/minikube/releases/latest/minikube-linux-$(dpkg --print-architecture)" \
 && chmod +x /usr/local/bin/minikube \
 && minikube version

# Install plugins at image build time so the container starts ready to run
# instead of downloading plugins on every boot.
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# Jenkins Configuration as Code: defines the admin user, the SonarQube server
# connection, and the pipeline job, so there is no click-through setup wizard.
COPY casc.yaml /var/jenkins_home/casc/jenkins.yaml

# Capped heap so Jenkins, SonarQube, Maven and minikube coexist comfortably.
# JAVA_OPTS is set here rather than in docker-compose.yml, because setting it
# there would replace this whole string and re-enable the setup wizard.
ENV CASC_JENKINS_CONFIG=/var/jenkins_home/casc/jenkins.yaml \
    JAVA_OPTS="-Xmx1024m -Xms256m -Djenkins.install.runSetupWizard=false -Dhudson.model.DownloadService.noSignatureCheck=true"

# NOTE: staying as root is a deliberate shortcut. The pipeline needs to use the
# mounted /var/run/docker.sock, and the socket's group id differs between hosts,
# so matching the "jenkins" user to it portably is fiddly. A real deployment
# would run an agent with a correctly mapped docker group instead of root.
# This is a local single-node development environment.
USER root
