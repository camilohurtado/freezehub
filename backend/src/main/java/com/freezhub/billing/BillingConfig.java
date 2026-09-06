package com.freezhub.billing;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the Stripe configuration (FZ-084).
 *
 * <p>Always registered, even with nothing configured. Billing then refuses at the point of
 * use — a checkout attempt answers {@code 503} and a webhook is rejected because nothing
 * can be verified — rather than the application failing to start. A deployment that has
 * not been given payment credentials yet should still serve freezes, which is the part
 * customers depend on.
 */
@Configuration
@EnableConfigurationProperties(StripeProperties.class)
public class BillingConfig {
}
