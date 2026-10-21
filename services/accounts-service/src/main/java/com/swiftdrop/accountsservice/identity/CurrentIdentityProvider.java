package com.swiftdrop.accountsservice.identity;

/** Security-owned adapter seam for #37. Never resolve identity from request headers or parameters. */
@FunctionalInterface
public interface CurrentIdentityProvider {
    TrustedIdentity currentIdentity();
}
