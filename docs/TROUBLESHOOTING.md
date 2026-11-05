# When it breaks

Ordered by when you are likely to hit it. Each entry gives the symptom, the
cause, and the fix.

Expect to hit something not on this list. Diagnosing it is worth more than
avoiding it, so the entries below say what the failure means rather than just
what to type.

First move, always:

```bash
./scripts/03-status.sh                  # what is actually running
cd ci && docker compose logs --tail=80 jenkins
cd ci && docker compose logs --tail=80 sonarqube
```

---

## Setup

### `bad interpreter: /usr/bin/env bash^M` or `: not found`

The scripts were checked out with Windows CRLF line endings, so the kernel is
looking for an interpreter whose name ends in a carriage return.
[.gitattributes](../.gitattributes) forces LF to prevent this, but a checkout
made before it existed keeps the old endings.

```bash
sed -i 's/\r$//' scripts/*.sh app/mvnw
```

### `Permission denied` running a script

Git does not track the execute bit on Windows.

```bash
chmod +x scripts/*.sh app/mvnw
```

### Preflight reports missing tools in a fresh Codespace

The devcontainer features are probably still installing. Wait for the setup
terminal to finish, then `./scripts/00-preflight.sh` again. If `docker info`
still fails, `sudo service docker start`.

---

## SonarQube

### It never reaches UP; logs mention `max virtual memory areas` or `vm.max_map_count`

Its embedded Elasticsearch needs a kernel setting the container cannot change
itself.

```bash
sudo sysctl -w vm.max_map_count=262144
cd ci && docker compose restart sonarqube
```

The bootstrap script does this, but it needs `sudo` to succeed.

### It exits immediately, or the logs end in `OutOfMemoryError`

Not enough RAM, usually because minikube already took it. Check with
`./scripts/03-status.sh`. Either start SonarQube before minikube, or give
minikube less:

```bash
minikube delete && MINIKUBE_MEMORY=3072 ./scripts/02-start-minikube.sh
```

### `Cannot authenticate to SonarQube` from the bootstrap script

`ci/.env` and the SonarQube volume have drifted apart — usually because the
volume was deleted but `ci/.env` was kept, or the password was changed in the
UI. Start that part clean:

```bash
cd ci && docker compose down
docker volume rm securepipe_sonarqube_data securepipe_sonarqube_extensions
rm ci/.env
./scripts/01-start-toolchain.sh
```

---

## Jenkins

### The container starts, then exits; logs show a JCasC error

[ci/jenkins/casc.yaml](../ci/jenkins/casc.yaml) failed to apply, and Jenkins
refuses to start with a configuration it cannot parse. The log names the
offending key.

```bash
cd ci && docker compose logs jenkins | grep -iA5 'casc\|configuration-as-code'
```

To get a working Jenkins while you fix it, disable JCasC and use the setup
wizard — the job can be created by hand (see below):

```bash
docker run -d --name jenkins-plain -p 8080:8080 \
  -e JAVA_OPTS=-Djenkins.install.runSetupWizard=false \
  securepipe/jenkins:latest
```

### The `vrsms-securepipe` job does not exist

The Job DSL script in `casc.yaml` did not run. Create it by hand — it takes two
minutes and is worth knowing how to do:

1. **New Item** → name `vrsms-securepipe` → **Pipeline** → OK
2. **Pipeline** section → Definition: **Pipeline script from SCM**
3. SCM: **Git**, Repository URL `https://github.com/bindpratapsingh/securepipe.git`
4. Branch: `*/main`, Script Path: `Jenkinsfile`
5. Save, then **Build Now**

The parameters appear after the first run — Jenkins learns them by executing
the `parameters` block.

### `docker: not found`, or `permission denied` on `/var/run/docker.sock`

The socket is not mounted, or the container is not running as root. Check:

```bash
docker exec securepipe-jenkins docker ps
docker exec securepipe-jenkins id
```

`id` should report `uid=0(root)`. If the socket is missing, recreate the
container: `cd ci && docker compose up -d --force-recreate jenkins`.

### The Sonar stage fails with `No SonarQube installation named 'SonarQube'`

JCasC did not apply the Sonar server config. **Manage Jenkins → System →
SonarQube servers**: add one named exactly `SonarQube`, URL
`http://sonarqube:9000`, and select the `sonar-token` credential. If that
credential is also missing, take the token from `ci/.env` and add it under
**Manage Jenkins → Credentials** as a *Secret text* with ID `sonar-token`.

---

## The build

### Maven cannot resolve `spring-boot-starter-parent:4.0.4`

Either no network, or Maven is too old. Spring Boot 4 requires Maven 3.9+, and
`./mvnw` pins 3.9.14 — so make sure you are invoking the wrapper, not a system
`mvn`.

```bash
docker exec securepipe-jenkins bash -c 'cd /var/jenkins_home/workspace/vrsms-securepipe/app && ./mvnw -v'
```

### The build is very slow every time

The Maven cache volume is not being used. The compose file mounts
`jenkins_m2:/root/.m2`, which assumes Jenkins runs as root with `HOME=/root`.
Verify:

```bash
docker exec securepipe-jenkins bash -c 'echo $HOME; ls /root/.m2/repository | head'
```

### The Sonar stage fails on the scanner version, or cannot resolve `sonar-maven-plugin`

The scanner and the server have to be roughly in step. `ci/docker-compose.yml`
runs `sonarqube:community`, which is always the latest release, and recent
servers reject scanners that are too old. The scanner version is a single
property in [app/pom.xml](../app/pom.xml):

```xml
<sonar-maven-plugin.version>5.0.0.4389</sonar-maven-plugin.version>
```

- *"Please update to a newer version of the scanner"* → raise it. The current
  versions are listed on
  [Maven Central](https://central.sonatype.com/artifact/org.sonarsource.scanner.maven/sonar-maven-plugin/versions).
- *Maven cannot resolve the plugin at all* → that exact version does not exist;
  pick one that does from the same page.
- To take the server out of the equation entirely, pin it instead of tracking
  latest — set a specific tag such as `sonarqube:10.7.0-community` in
  `ci/docker-compose.yml`, then `docker compose up -d --force-recreate sonarqube`.

Either way, `RUN_SONAR=false` lets you keep working on the other stages while
you sort it out.

### `Quality Gate` hangs, then fails after 10 minutes

The SonarQube webhook is missing, so the callback never arrives. Check and
recreate it:

```bash
. ci/.env
curl -s -u "admin:${SONAR_ADMIN_PASSWORD}" http://localhost:9000/api/webhooks/list
curl -s -u "admin:${SONAR_ADMIN_PASSWORD}" -X POST \
  --data-urlencode "name=jenkins" \
  --data-urlencode "url=http://jenkins:8080/sonarqube-webhook/" \
  http://localhost:9000/api/webhooks/create
```

The URL must use the hostname `jenkins`, not `localhost` — SonarQube is calling
out from inside its own container.

### `Trivy Scan` fails on `--template @/usr/local/share/trivy/templates/html.tpl`

The template download in the Jenkins image failed. The HTML report is cosmetic;
the gate does not need it.

```bash
docker exec securepipe-jenkins curl -fsSLo /usr/local/share/trivy/templates/html.tpl \
  https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/html.tpl
```

### `Trivy Scan` fails and you did *not* ask it to

Read the report artifact before assuming it is a bug. A new CRITICAL CVE in the
Alpine base is entirely possible — that is the gate doing its job, not a
malfunction. Confirm what it found:

```bash
docker exec securepipe-jenkins trivy image --severity CRITICAL \
  --cache-dir /var/jenkins_home/trivy-cache vrsms-server:latest
```

If it is real and unfixable, either rebuild on a newer base or add the CVE to
[ci/trivy/.trivyignore](../ci/trivy/.trivyignore) **with a reason and a date**.

### Trivy cannot download its vulnerability database

Usually GitHub Container Registry rate-limiting. Retry; the database is cached
in the `jenkins_home` volume once it lands.

---

## Deploy

### `kubectl` from the pipeline times out, or `connection refused` on 192.168.49.2:8443

The headline problem of this project. The Jenkins container is not on the
minikube Docker network.

```bash
./scripts/02-start-minikube.sh          # attaches it and verifies
# or directly:
docker network connect minikube securepipe-jenkins
docker exec securepipe-jenkins kubectl get nodes
```

### `error loading config file ... ca.crt: no such file or directory`

The kubeconfig references its certificates by absolute path under
`$HOME/.minikube`, and that path does not resolve inside the container. It
happens when the mounts were created before the directories existed, so Docker
made them root-owned and empty.

```bash
docker exec securepipe-jenkins ls -la "$HOME/.minikube"    # expect certs here
mkdir -p "$HOME/.kube" "$HOME/.minikube"
cd ci && docker compose up -d --force-recreate jenkins
./scripts/02-start-minikube.sh
```

### Pods show `ErrImagePull` or `ImagePullBackOff`

The image never made it into the cluster. minikube has its own container
runtime, so a host-built image is invisible to it until loaded.

```bash
minikube image ls | grep vrsms
minikube image load vrsms-server:latest
# fallback, if the above fails:
docker save vrsms-server:latest | docker exec -i minikube docker load
```

Also confirm the chart still has `imagePullPolicy: IfNotPresent` — `Always`
sends Kubernetes to Docker Hub for an image that was never published.

### `helm upgrade` fails with `timed out waiting for the condition`

`--wait` did its job: the pods never became ready. Find out why rather than
rerunning.

```bash
kubectl -n vrsms get pods
kubectl -n vrsms describe pod -l app.kubernetes.io/name=vrsms
kubectl -n vrsms logs -l app.kubernetes.io/name=vrsms --tail=100
kubectl -n vrsms get events --sort-by=.lastTimestamp | tail -20
```

Common causes, in order: the init container is still waiting because PostgreSQL
has not started; the app cannot reach the database; the JVM was OOM-killed
because `resources.limits.memory` is too low for it.

### The app pod crash-loops with a Hibernate or JDBC error

Check the datasource values that actually reached the container, then the
database itself:

```bash
kubectl -n vrsms exec deploy/vrsms -- env | grep SPRING_DATASOURCE
kubectl -n vrsms logs deploy/vrsms-postgres --tail=50
```

If Hibernate is complaining about a missing table or type, `ddlAuto` is not set
to `update` — the chart sets it, but `helm get values vrsms -n vrsms` will show
whether an override replaced it.

### `helm upgrade` fails with `another operation is in progress`

A previous run died mid-release.

```bash
helm rollback vrsms -n vrsms
# if that will not work either:
helm uninstall vrsms -n vrsms
```

---

## Resources

### Builds get killed, or the Codespace becomes unresponsive

Everything is running at once and memory ran out.

```bash
./scripts/03-status.sh                   # per-container memory
```

In order of preference: stop SonarQube while working on the deploy stages
(`docker stop securepipe-sonarqube`, and build with `RUN_SONAR` off); give
minikube less (`MINIKUBE_MEMORY=3072`); or move to the 8-core machine, which
costs double the core-hours.

### Running out of disk

```bash
docker system df
docker image prune -a          # untagged and unused images
minikube ssh -- docker image prune -a
```

Old `vrsms-server:<build-number>` tags accumulate, one per build.

### Codespace core-hours running low

```bash
./scripts/stop.sh && gh codespace stop
```

Check usage at <https://github.com/settings/billing>. A 4-core machine burns 4
core-hours per wall-clock hour against 120 free per month. `./scripts/stop.sh`
without `--purge` keeps every cache, so restarting is about a minute.

---

## Starting completely clean

```bash
./scripts/stop.sh --purge
rm -f ci/.env
./scripts/01-start-toolchain.sh
./scripts/02-start-minikube.sh
```

Roughly ten minutes, and it discards Jenkins build history and SonarQube
analysis history.
