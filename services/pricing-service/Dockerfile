# syntax=docker/dockerfile:1
# ==============================================================================
# SwiftDrop Pricing & Zones Service Container Image (Issue #128)
# Multi-stage reproducible build for Pricing & Zones Microservice (Java 21)
# ==============================================================================

# Build Stage
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /build

# Copy shared contracts and libraries
COPY contracts/grpc contracts/grpc
COPY shared/proto-contracts shared/proto-contracts
COPY shared/security-adapter shared/security-adapter

# Build and install shared monorepo dependencies into local Maven cache
RUN chmod +x shared/proto-contracts/mvnw && \
    chmod +x shared/security-adapter/mvnw && \
    (cd shared/proto-contracts && ./mvnw -B clean install -DskipTests) && \
    (cd shared/security-adapter && ./mvnw -B clean install -DskipTests)

# Copy service source
COPY services/pricing-service services/pricing-service

# Build executable Spring Boot JAR
RUN chmod +x services/pricing-service/mvnw && \
    (cd services/pricing-service && ./mvnw -B clean package -DskipTests) && \
    cp services/pricing-service/target/pricing-service-*.jar /build/app.jar

# Runtime Stage
FROM eclipse-temurin:21-jre-jammy

LABEL org.opencontainers.image.title="SwiftDrop Pricing & Zones Service" \
      org.opencontainers.image.description="Delivery fee calculation, zone rates, and quote lifecycle verification" \
      org.opencontainers.image.source="https://github.com/Suthankan1/swiftdrop" \
      org.opencontainers.image.licenses="Apache-2.0"

# Non-root execution
RUN groupadd -g 10001 swiftdrop && \
    useradd -u 10001 -g swiftdrop -d /home/swiftdrop -m -s /sbin/nologin swiftdrop

WORKDIR /app

COPY --from=builder --chown=swiftdrop:swiftdrop /build/app.jar /app/app.jar

USER 10001:10001

EXPOSE 8082 9083

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8082/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
