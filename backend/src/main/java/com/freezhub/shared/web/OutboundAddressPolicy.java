package com.freezhub.shared.web;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Where a customer-supplied destination is allowed to point (`FZ-126`, `OI-23`).
 *
 * <p>A webhook URL is attacker-chosen by design: any administrator of any tenant can set
 * one, and the task then makes an HTTP request to it from inside the deployment's network.
 * Without this, that reaches the loopback interface, the VPC, and — on a container runtime
 * — the link-local address that serves the task's own IAM credentials.
 *
 * <p><strong>Resolved, not parsed.</strong> The check that matters is what the name points
 * at, not what it looks like: {@code https://internal.example.com/} is a perfectly ordinary
 * URL that can resolve to {@code 10.0.0.5}. Every address the name resolves to has to pass,
 * because a name with several records only needs one to be useful.
 *
 * <p><strong>Per request, not per save.</strong> Validating when the integration is stored
 * leaves a name whose records can be changed the minute after. This runs on the way out,
 * every time.
 *
 * <p>What it does not close: the window between this resolution and the client's own, which
 * is a DNS rebind. Closing that means connecting to a pinned address with the {@code Host}
 * header set by hand, which is a different shape of client. The durable answer is egress
 * control in the network — a security group or a proxy — which is `FZ-123`'s to decide and
 * which this does not replace.
 */
@Component
public class OutboundAddressPolicy {

    private final Function<String, InetAddress[]> resolver;

    OutboundAddressPolicy() {
        this(host -> {
            try {
                return InetAddress.getAllByName(host);
            } catch (UnknownHostException unknown) {
                throw new EgressRefusedException(
                        "the destination's host name does not resolve", unknown);
            }
        });
    }

    /** Injectable so the rules can be tested without depending on what DNS says today. */
    public OutboundAddressPolicy(Function<String, InetAddress[]> resolver) {
        this.resolver = resolver;
    }

    /**
     * @throws EgressRefusedException with a message the customer is meant to read — it says
     *         what class of address was refused and never the address itself, which would
     *         confirm internal topology to whoever pointed a name at it
     */
    public void requireCallable(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            // Belt and braces: the configuration endpoint already requires https. This is
            // the check that still applies after a redirect tries to downgrade.
            throw new EgressRefusedException("the destination is not an https URL");
        }

        /*
         * `https://slack.com@10.0.0.5/` starts with "https://" and is a valid URI whose
         * host is 10.0.0.5. The userinfo is refused outright rather than ignored: no
         * legitimate webhook carries credentials in the authority, and the only thing it
         * reliably does is make a URL read as one host while addressing another.
         */
        if (uri.getUserInfo() != null) {
            throw new EgressRefusedException(
                    "the destination carries credentials in its address, which FreezeHub will not call");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new EgressRefusedException("the destination has no host");
        }

        for (InetAddress address : resolver.apply(host)) {
            if (isInternal(address)) {
                throw new EgressRefusedException(
                        "the destination resolves to a private, loopback or link-local address, "
                                + "which FreezeHub will not call");
            }
        }
    }

    private boolean isInternal(InetAddress address) {
        return address.isLoopbackAddress()       // 127.0.0.0/8, ::1
                || address.isLinkLocalAddress()  // 169.254.0.0/16 — the metadata service
                || address.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || address.isAnyLocalAddress()   // 0.0.0.0, ::
                || address.isMulticastAddress()
                || isUniqueLocal(address)
                || isCarrierGrade(address);
    }

    /** fc00::/7. {@code isSiteLocalAddress} covers the deprecated fec0::/10 and not this. */
    private boolean isUniqueLocal(InetAddress address) {
        return address instanceof Inet6Address
                && (address.getAddress()[0] & 0xfe) == 0xfc;
    }

    /** 100.64.0.0/10, which is carrier-grade NAT and also where some runtimes put pods. */
    private boolean isCarrierGrade(InetAddress address) {
        byte[] octets = address.getAddress();
        return octets.length == 4
                && (octets[0] & 0xff) == 100
                && (octets[1] & 0xc0) == 0x40;
    }

}
