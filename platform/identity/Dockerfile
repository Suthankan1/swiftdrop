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

# Copy branded SwiftDrop theme
COPY --chown=keycloak:keycloak platform/identity/themes/swiftdrop /opt/keycloak/themes/swiftdrop

USER 1000

EXPOSE 8080 8443

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD [ "bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080" ] || exit 1

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start"]
