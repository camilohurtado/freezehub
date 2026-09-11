package com.freezhub.shared.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

/**
 * Where a customer-supplied destination may point (`FZ-126`, `OI-23`).
 *
 * <p>The resolver is stubbed rather than real: these are rules about addresses, and a test
 * that depended on what DNS says today would be testing the internet.
 */
class OutboundAddressPolicyTest {

    private static OutboundAddressPolicy resolvingTo(String... addresses) {
        return new OutboundAddressPolicy(host -> {
            try {
                InetAddress[] resolved = new InetAddress[addresses.length];
                for (int i = 0; i < addresses.length; i++) {
                    resolved[i] = InetAddress.getByName(addresses[i]);
                }
                return resolved;
            } catch (UnknownHostException impossible) {
                throw new IllegalStateException(impossible);
            }
        });
    }

    private static void refuses(OutboundAddressPolicy policy, String url, String because) {
        assertThatThrownBy(() -> policy.requireCallable(URI.create(url)))
                .isInstanceOf(EgressRefusedException.class)
                .hasMessageContaining(because);
    }

    @Test
    void allowsAnOrdinaryPublicDestination() {
        assertThatCode(() -> resolvingTo("93.184.216.34")
                .requireCallable(URI.create("https://hooks.example.com/services/T000/B000")))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesLoopback() {
        refuses(resolvingTo("127.0.0.1"), "https://localhost/hook", "private, loopback or link-local");
    }

    @Test
    void refusesTheLinkLocalMetadataAddress() {
        // 169.254.169.254 is where a container runtime serves the task's own credentials.
        // It is the address this whole story exists for.
        refuses(resolvingTo("169.254.169.254"), "https://metadata.example.com/",
                "private, loopback or link-local");
    }

    @Test
    void refusesPrivateRanges() {
        refuses(resolvingTo("10.0.0.5"), "https://internal.example.com/", "private");
        refuses(resolvingTo("172.16.4.9"), "https://internal.example.com/", "private");
        refuses(resolvingTo("192.168.1.20"), "https://internal.example.com/", "private");
    }

    @Test
    void refusesIpv6LoopbackAndUniqueLocal() {
        // fc00::/7 is not covered by isSiteLocalAddress, which is about the deprecated
        // fec0::/10 — the one every naive implementation of this check misses.
        refuses(resolvingTo("::1"), "https://v6.example.com/", "private");
        refuses(resolvingTo("fd00::1"), "https://v6.example.com/", "private");
    }

    @Test
    void refusesCarrierGradeNat() {
        refuses(resolvingTo("100.64.0.1"), "https://cgnat.example.com/", "private");
    }

    @Test
    void refusesWhenAnyOneRecordIsInternal() {
        /*
         * A name with several records only needs one of them to be useful: resolve twice
         * and you may get the public one first and the internal one second. Every address
         * has to pass, not the first.
         */
        refuses(resolvingTo("93.184.216.34", "10.0.0.5"), "https://split.example.com/", "private");
    }

    @Test
    void refusesAUserinfoAuthority() {
        /*
         * `https://hooks.slack.com@10.0.0.5/` starts with "https://" and reads like Slack.
         * Its host is 10.0.0.5. The scheme check that existed before this story passed it.
         */
        refuses(resolvingTo("10.0.0.5"), "https://hooks.slack.com@10.0.0.5/",
                "credentials in its address");
    }

    @Test
    void refusesAnythingThatIsNotHttps() {
        // Not reachable through the configuration endpoint, which requires https — but it
        // is reachable through a redirect, which is the other half of this story.
        refuses(resolvingTo("93.184.216.34"), "http://hooks.example.com/", "not an https URL");
    }

    @Test
    void refusesANameThatDoesNotResolve() {
        OutboundAddressPolicy policy = new OutboundAddressPolicy(host -> {
            throw new EgressRefusedException("the destination's host name does not resolve");
        });
        refuses(policy, "https://nowhere.example.com/", "does not resolve");
    }

}
