# syntax=docker/dockerfile:1
#
# Runtime image for the VRSMS backend.
#
# This expects the jar to already be built, because the Jenkins pipeline
# compiles and tests the code in an earlier stage. Rebuilding inside Docker
# would run Maven twice and double the pipeline time.
#
#   ./mvnw -B clean package -DskipTests
#   docker build -t vrsms-server:dev .
#
# Hardening choices worth knowing:
#   - Alpine JRE base: a much smaller package surface than a JDK or a
#     Debian-based image, which means far fewer CVEs for Trivy to find.
#   - JRE not JDK: no compiler or dev tooling shipped to production.
#   - Non-root user: a container escape does not land on uid 0.
#   - MaxRAMPercentage: the JVM sizes its heap from the container's cgroup
#     memory limit instead of the host's total RAM.

FROM eclipse-temurin:17-jre-alpine

# curl is used by the HEALTHCHECK below, and by the pipeline's smoke test.
# The uid is pinned to 10001 so it matches podSecurityContext.runAsUser in the
# Helm chart's values.yaml - otherwise Kubernetes would run the process under
# a uid that does not own the jar.
RUN apk add --no-cache curl \
    && addgroup -g 10001 -S spring \
    && adduser -u 10001 -S spring -G spring

WORKDIR /app

ARG JAR_FILE=target/vrsms-server.jar
COPY --chown=spring:spring ${JAR_FILE} app.jar

USER spring

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC"

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# exec form via sh so JAVA_OPTS expands, but exec keeps the JVM as PID 1
# so it receives SIGTERM from Kubernetes and shuts down gracefully.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
