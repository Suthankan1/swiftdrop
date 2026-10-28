package com.swiftdrop.communicationsservice.webhook.validation;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Pluggable DNS resolution strategy for validating webhook destinations against SSRF.
 */
public interface DnsLookupResolver {

    /**
     * Resolves all IP addresses for the given hostname.
     *
     * @param host hostname to resolve
     * @return array of resolved InetAddress instances
     * @throws UnknownHostException if host cannot be resolved
     */
    InetAddress[] resolve(String host) throws UnknownHostException;
}
