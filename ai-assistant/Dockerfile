# syntax=docker/dockerfile:1
# SwiftDrop AI delivery assistant (Gemini function calling over existing read APIs), Java 21

FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /build
COPY contracts/grpc contracts/grpc
COPY shared/proto-contracts shared/proto-contracts
COPY shared/security-adapter shared/security-adapter
RUN chmod +x shared/proto-contracts/mvnw shared/security-adapter/mvnw && \
    (cd shared/proto-contracts && ./mvnw -B clean install -DskipTests) && \
    (cd shared/security-adapter && ./mvnw -B clean install -DskipTests)
COPY ai-assistant ai-assistant
RUN chmod +x ai-assistant/mvnw && \
    (cd ai-assistant && ./mvnw -B clean package -DskipTests) && \
    cp ai-assistant/target/ai-assistant-*.jar /build/app.jar

FROM eclipse-temurin:21-jre-jammy
LABEL org.opencontainers.image.title="SwiftDrop AI Assistant" \
      org.opencontainers.image.description="Read-only delivery assistant backed by Gemini function calling" \
      org.opencontainers.image.source="https://github.com/Suthankan1/swiftdrop" \
      org.opencontainers.image.licenses="Apache-2.0"
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get upgrade -y && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd -g 10001 swiftdrop && \
    useradd -u 10001 -g swiftdrop -d /home/swiftdrop -m -s /sbin/nologin swiftdrop
WORKDIR /app
COPY --from=builder --chown=swiftdrop:swiftdrop /build/app.jar /app/app.jar
USER 10001:10001
EXPOSE 8086
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8086/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
