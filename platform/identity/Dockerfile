# syntax=docker/dockerfile:1
# ==============================================================================
# SwiftDrop Keycloak Identity Container Image (Issue #128)
# Pinned official Keycloak 26.8.1 runtime with branded SwiftDrop theme
# ==============================================================================

FROM quay.io/keycloak/keycloak:26.8.1

LABEL org.opencontainers.image.title="SwiftDrop Keycloak Identity Provider" \
      org.opencontainers.image.description="Keycloak IAM runtime with customized SwiftDrop login theme" \
      org.opencontainers.image.source="https://github.com/Suthankan1/swiftdrop" \
      org.opencontainers.image.licenses="Apache-2.0"

# Keycloak 26.8.1 bundles Guava 33.6.0-jre (CVE-2026-102554). Replace only
# that artifact with the verified fixed release. Keep its fast-jar classpath
# filename, then rebuild Quarkus metadata; no dependency is fetched at startup.
ADD --checksum=sha256:b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c --chown=keycloak:keycloak --chmod=0644 \
    https://repo.maven.apache.org/maven2/com/google/guava/guava/33.7.2-jre/guava-33.7.2-jre.jar \
    /opt/keycloak/lib/lib/main/com.google.guava.guava-33.6.0-jre.jar
RUN /opt/keycloak/bin/kc.sh build

# Copy branded SwiftDrop theme
COPY --chown=keycloak:keycloak platform/identity/themes/swiftdrop /opt/keycloak/themes/swiftdrop

USER 1000

EXPOSE 8080 8443

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD [ "bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080" ] || exit 1

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start"]
