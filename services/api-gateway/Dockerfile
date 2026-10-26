# syntax=docker/dockerfile:1
# ==============================================================================
# SwiftDrop API Gateway Container Image (Issue #128)
# Multi-stage reproducible build for Spring Cloud Gateway (Java 21)
# ==============================================================================

# Build Stage
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /build

# Copy service source
COPY services/api-gateway services/api-gateway

# Build executable Spring Boot JAR with committed Maven wrapper
RUN chmod +x services/api-gateway/mvnw && \
    (cd services/api-gateway && ./mvnw -B clean package -DskipTests) && \
    cp services/api-gateway/target/api-gateway-*.jar /build/app.jar

# Runtime Stage
FROM eclipse-temurin:21-jre-jammy

LABEL org.opencontainers.image.title="SwiftDrop API Gateway" \
      org.opencontainers.image.description="Spring Cloud API Gateway routing edge and rate limiter" \
      org.opencontainers.image.source="https://github.com/Suthankan1/swiftdrop" \
      org.opencontainers.image.licenses="Apache-2.0"

# Non-root execution
RUN groupadd -g 10001 swiftdrop && \
    useradd -u 10001 -g swiftdrop -d /home/swiftdrop -m -s /sbin/nologin swiftdrop

WORKDIR /app

COPY --from=builder --chown=swiftdrop:swiftdrop /build/app.jar /app/app.jar

USER 10001:10001

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
