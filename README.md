# SecurePipe

A Jenkins CI/CD pipeline for the [VRSMS](https://github.com/bindpratapsingh/VRSMS---Main)
backend — a Spring Boot 4 service for a video rental store — that builds and
tests the application, analyses it with SonarQube, scans the resulting
container image with Trivy, and deploys it to a local Kubernetes cluster with
Helm.

**The Trivy stage is a hard gate: a CRITICAL vulnerability stops the build
before anything reaches the cluster.**

---

## The pipeline

```
┌──────────────────┐
│ Checkout         │  git, pinned to a commit
└────────┬─────────┘
         │
┌────────▼─────────┐
│ Build &          │  ./mvnw clean verify
│ Unit Test        │  JUnit report + JaCoCo coverage
└────────┬─────────┘  integration + ui tagged tests excluded
         │
┌────────▼─────────┐
│ SonarQube        │  mvn sonar:sonar -> SonarQube server
│ Analysis         │  uploads the JaCoCo XML report
└────────┬─────────┘
         │
┌────────▼─────────┐
│ Quality Gate     │  waitForQualityGate, via SonarQube webhook
└────────┬─────────┘  advisory by default — see "Why the Sonar gate is advisory"
         │
┌────────▼─────────┐
│ Docker Build     │  Alpine JRE base, non-root uid 10001
└────────┬─────────┘
         │
┌────────▼─────────┐
│ Trivy Scan       │  full report at every severity (archived)
│                  │  then: --severity CRITICAL --exit-code 1
└────────┬─────────┘
         │           ◀── BUILD STOPS HERE on a CRITICAL finding
┌────────▼─────────┐
│ Helm Deploy      │  minikube image load, then helm upgrade --install
└────────┬─────────┘  --wait, so the stage fails if pods never go ready
         │
┌────────▼─────────┐
│ Smoke Test       │  curl /actuator/health from inside the pod
└──────────────────┘
```

Every stage is defined in [Jenkinsfile](Jenkinsfile) as a declarative pipeline.

## Layout

| Path | What it is |
|---|---|
| [Jenkinsfile](Jenkinsfile) | The pipeline. Eight stages, six build parameters. |
| [app/](app/) | The VRSMS Spring Boot backend, plus its `Dockerfile`. |
| [app/Dockerfile](app/Dockerfile) | Hardened runtime image: Alpine JRE, non-root, container-aware heap. |
| [app/Dockerfile.vulnerable](app/Dockerfile.vulnerable) | Deliberately insecure base, used to prove the Trivy gate works. |
| [ci/docker-compose.yml](ci/docker-compose.yml) | The toolchain: a Jenkins controller and a SonarQube server. |
| [ci/jenkins/Dockerfile](ci/jenkins/Dockerfile) | Jenkins with docker, trivy, kubectl, helm and minikube baked in. |
| [ci/jenkins/casc.yaml](ci/jenkins/casc.yaml) | Jenkins Configuration as Code — no setup wizard, job defined in code. |
| [charts/vrsms/](charts/vrsms/) | Helm chart: the app, its PostgreSQL, ConfigMap, Secret, Service. |
| [scripts/](scripts/) | Bootstrap and teardown. Start here. |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Failure modes across the toolchain, and how to diagnose each. |

## Running it

The stack peaks at roughly **7.5 GB of RAM** — Jenkins ~1.2 GB, SonarQube
~2 GB, minikube ~4 GB, plus a Maven build. It does not fit on a small laptop,
so the repository ships a devcontainer sized for it.

### GitHub Codespaces (what this is built for)

1. **Code → Codespaces → New with options**, and pick the **4-core / 16 GB** machine.
2. Wait for the devcontainer to finish; [scripts/00-preflight.sh](scripts/00-preflight.sh)
   runs automatically and reports what is present.
3. Then:

```bash
./scripts/01-start-toolchain.sh   # SonarQube + Jenkins   (~10 min first run)
./scripts/02-start-minikube.sh    # Kubernetes cluster     (~3 min)
./scripts/03-status.sh            # URLs and credentials
```

4. Open Jenkins on the forwarded port 8080, open the **vrsms-securepipe** job,
   and click **Build**.

```bash
./scripts/stop.sh                 # end of session; keeps all caches
```

### Any Linux host with ~10 GB free

The same three scripts work, given docker, kubectl, helm, minikube and a JDK 17.
Run `./scripts/00-preflight.sh` first to confirm.

## Seeing the security gate work

```bash
./scripts/demo-trivy-gate.sh
```

This builds the application on both bases and scans each with the pipeline's
own command. The hardened image passes; the one on the deprecated
`openjdk:17-jdk-slim` base fails with CRITICAL findings.

To watch it inside Jenkins, run the job with `DOCKERFILE=Dockerfile.vulnerable`
and `IGNORE_UNFIXED=false`. The build stops at **Trivy Scan** and the Helm
Deploy stage never runs.

## Design decisions

**The application was not written for CI, so three things had to change first.**
`ServerApplicationTests` is a `@SpringBootTest` that needs a live PostgreSQL;
`LoginUITest` drives a real Chrome browser against a running frontend. Both
were given JUnit tags (`integration`, `ui`) that [the pom](app/pom.xml)
excludes, rather than being deleted — they are real tests, they just cannot run
on a CI agent. Selenium was also moved from compile to test scope, which had
been shipping the entire browser-automation stack inside the production jar.

**PostgreSQL is deployed in-cluster, not swapped for H2.** The entities map
their enums with `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`, which generates native
`CREATE TYPE ... AS ENUM` DDL. H2 cannot produce that, so an in-memory
substitute would have failed at schema generation. The chart runs a real
PostgreSQL on `emptyDir` storage.

**The image is built once and side-loaded into the cluster.** The jar is
compiled in the test stage, so [app/Dockerfile](app/Dockerfile) only copies it
in — building inside Docker would run Maven a second time. minikube has its own
container runtime, so the image is pushed in with `minikube image load` and the
chart sets `imagePullPolicy: IfNotPresent`; `Always` would send Kubernetes
looking for it on Docker Hub.

**Liveness and readiness use different endpoints.** `/actuator/health` includes
the database health indicator, so using it for liveness would turn a brief
PostgreSQL outage into a pod restart loop. Liveness uses
`/actuator/health/liveness`, which excludes it.

**Why the Sonar gate is advisory.** SonarQube's built-in "Sonar way" gate
judges *new* code, and on a first analysis an entire pre-existing codebase
counts as new — its 80%-coverage condition fails every build until the test
suite catches up. The stage reports the gate and marks the build unstable;
`FAIL_ON_QUALITY_GATE=true` makes it blocking once the gate is tuned for this
codebase. The gate that genuinely stops a deploy here is Trivy's.

**Known shortcuts.** Jenkins runs builds on the controller rather than on
agents, and runs as root so it can use the mounted Docker socket; PostgreSQL
uses `emptyDir`, so its data is lost on restart; the chart's credentials come
from `values.yaml`, which a Kubernetes Secret only base64-encodes rather than
encrypts. Each is a reasonable trade for a local single-node environment and
each is called out at the point it appears in the code.

## The application

Spring Boot 4.0.4 on Java 17, built with Maven. REST controllers for
authentication, inventory, rentals, member history, wishlists, and staff and
manager operations, over Spring Data JPA and PostgreSQL, with Twilio Verify for
OTP. The documentation set — SRS, DFDs, use case, class and sequence diagrams —
lives in the [original repository](https://github.com/bindpratapsingh/VRSMS---Main).
