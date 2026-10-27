package com.swiftdrop.communicationsservice.webhook.validation;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Standard DNS resolver using JVM InetAddress resolution.
 */
@Component
public class DefaultDnsLookupResolver implements DnsLookupResolver {

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }
}
