package com.freezhub.shared.web;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

}
