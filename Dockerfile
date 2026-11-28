# ==============================================================================
# PayMesh â€” Render deployment image
# ==============================================================================
# Multi-stage build:
#   Stage 1 (builder) compiles the executable Spring Boot JAR with Maven + JDK 21.
#   Stage 2 (runtime) runs the JAR on a minimal JRE 21 as a non-root user.
#
# Render sets the PORT environment variable; the application binds to it via
# server.port=${PORT:${SERVER_PORT:8080}} in application.properties.
# ==============================================================================

# --- Stage 1: build ----------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Copy Maven inputs before application sources so dependency layers can be reused.
COPY pom.xml .
COPY docker/maven-settings.xml ./maven-settings.xml
COPY mvnw .
COPY mvnw.cmd .
COPY .mvn/ .mvn/

# Pre-fetch dependencies in a separate layer so source changes rebuild less.
RUN mvn -B -C -s maven-settings.xml dependency:go-offline

# Copy the source tree and package the executable JAR, skipping tests.
COPY src/ src/
RUN mvn -B -C -s maven-settings.xml -DskipTests package

# --- Stage 2: runtime --------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

WORKDIR /app

# curl is used only by the container health check.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Create a non-root user for the application.
RUN groupadd -r paymesh && useradd -r -g paymesh -d /app -s /sbin/nologin paymesh

# Copy the executable JAR from the builder stage. All application resources,
# Flyway migrations and the dashboard are packaged inside the JAR.
COPY --from=builder /build/target/upi-offline-mesh-0.0.1-SNAPSHOT.jar /app/app.jar

# Make the runtime user own the application files.
RUN chown -R paymesh:paymesh /app

USER paymesh

# Render injects PORT; Spring Boot binds to it. Fall back to 8080 locally.
ENV SPRING_PROFILES_ACTIVE=production
ENV PAYMESH_REQUIRE_PERSISTENT_RSA_KEY=true
# Avoid competing for Render's 0.1 CPU with the optimizing compiler during startup.
ENV JDK_JAVA_OPTIONS="-XX:TieredStopAtLevel=1"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent "http://127.0.0.1:${PORT:-${SERVER_PORT:-8080}}/health" || exit 1

# Spring's relaxed binding reads SERVER_PORT directly. Give Render's PORT precedence.
ENTRYPOINT ["sh", "-c", "export SERVER_PORT=\"${PORT:-${SERVER_PORT:-8080}}\"; exec java -jar /app/app.jar \"$@\"", "--"]
