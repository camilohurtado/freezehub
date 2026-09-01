# The image ECS runs (FZ-064).
#
# Multi-stage so the result carries a JRE and a jar, not a JDK, Maven and the whole
# dependency cache — and so `docker build backend/` works on a laptop exactly as it does
# in CI, with no "build the jar first" step to forget.

FROM eclipse-temurin:21-jdk AS build

WORKDIR /build

# The wrapper and the POM first, on their own: dependencies change far less often than
# source, so this layer survives most builds and the download is paid once.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

COPY src/ src/

# Tests are skipped here on purpose: CI has already run the full suite against a real
# PostgreSQL (verify.yml), and Testcontainers cannot start a database inside this build.
# Running them here would either fail or quietly skip, and both are worse than not trying.
RUN ./mvnw --batch-mode --no-transfer-progress clean package -DskipTests


FROM eclipse-temurin:21-jre AS runtime

# Not root. A container that does not need to write anywhere should not be able to.
RUN groupadd --system freezehub && useradd --system --gid freezehub freezehub

WORKDIR /app
COPY --from=build --chown=freezehub:freezehub /build/target/*.jar app.jar

USER freezehub
EXPOSE 8080

# Container-aware by default in modern JVMs: the heap is sized from the Fargate task's
# memory limit rather than the host's, so there is no -Xmx to keep in step with the
# task definition.
#
# `exec` form, so the JVM is PID 1 and receives the SIGTERM ECS sends on a deployment —
# without it a shell would swallow the signal and every deploy would end in a kill.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
