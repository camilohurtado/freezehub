package com.freezhub.shared.web;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Timeouts on everything FreezeHub calls out to (`FZ-065`).
 *
 * <p>Spring Boot's {@code RestClient.Builder} arrives with no read timeout, so a receiver
 * that accepts the connection and then never answers blocks the calling thread for as long
 * as it likes. That is not a hypothetical: a customer's webhook endpoint sitting behind a
 * wedged proxy does exactly this, and it is invisible in testing because a receiver that is
 * merely broken answers quickly.
 *
 * <p>What made it worth fixing before beta is the blast radius. Delivery runs inside a
 * transaction, on one dispatcher shared by every organization: a single hung endpoint held
 * a database connection open and stopped every other tenant's announcements behind it.
 * Verified with a socket that accepts and never replies — see {@code WebhookTimeoutTest}.
 *
 * <p>A customizer rather than a timeout at each call site, so a sender added later inherits
 * it without anybody remembering to. Deliberately generous: a slow receiver should be
 * retried by {@code RetryPolicy}, not called a failure on its first slow day.
 */
@Configuration
public class OutboundHttpConfig {

    private final Duration connectTimeout;
    private final Duration readTimeout;

    public OutboundHttpConfig(
            @Value("${freezehub.outbound-http.connect-timeout:5s}") Duration connectTimeout,
            @Value("${freezehub.outbound-http.read-timeout:10s}") Duration readTimeout) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    @Bean
    RestClientCustomizer outboundHttpTimeouts() {
        return builder -> builder.requestFactory(ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(connectTimeout)
                        .withReadTimeout(readTimeout)));
    }

    /**
     * The client that calls **customer-supplied** destinations (`FZ-126`, `OI-23`).
     *
     * <p>Separate from the builder above, and deliberately not applied to every
     * {@code RestClient} in the application: the demo-request notifier posts to a webhook
     * this organization configures for itself, and refusing to call a private address
     * would be wrong there. What is guarded here is the set of destinations an
     * administrator of any tenant can point anywhere.
     *
     * <p>Two properties, and the second one only works because of the first:
     *
     * <ul>
     *   <li><strong>Redirects are not followed.</strong> Checking the address of a request
     *       that is then redirected somewhere else checks nothing, and a `302` from an
     *       attacker's own HTTPS endpoint to {@code http://169.254.169.254/} is the whole
     *       attack. A 3xx is turned into a delivery failure below rather than returned,
     *       because a response nobody followed is not a delivery.</li>
     *   <li><strong>Every request's destination is resolved and checked</strong> by
     *       {@link OutboundAddressPolicy}, on the way out, every time.</li>
     * </ul>
     */
    @Bean
    RestClient outboundDeliveryRestClient(OutboundAddressPolicy policy) {
        return deliveryClient(policy, connectTimeout, readTimeout);
    }

    /**
     * The assembly, separated from the bean so a test can drive it with a policy of its
     * own. The redirect behaviour and the address check are independent halves, and a test
     * for one has to be able to switch the other off — with the real policy, every local
     * test server is refused for being loopback before a redirect can be observed at all.
     */
    public static RestClient deliveryClient(OutboundAddressPolicy policy,
                                            Duration connectTimeout, Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    policy.requireCallable(request.getURI());

                    ClientHttpResponse response = execution.execute(request, body);
                    if (response.getStatusCode().is3xxRedirection()) {
                        // Not followed, and not silently counted as delivered: the
                        // customer's endpoint asked us to go somewhere else, and the
                        // answer is that we do not.
                        throw new EgressRefusedException(
                                "the destination redirected the delivery, and FreezeHub does "
                                        + "not follow redirects to a second address");
                    }
                    return response;
                })
                .build();
    }

}
