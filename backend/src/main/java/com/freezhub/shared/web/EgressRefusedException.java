package com.freezhub.shared.web;

/**
 * A destination FreezeHub declined to call (`FZ-126`).
 *
 * <p>Unchecked on purpose. It travels up through the sender untouched so that its message
 * is the one recorded against the notification and shown on the notifications screen —
 * the acceptance criterion is that a destination which will never work says so, rather
 * than failing as a generic "could not be reached".
 *
 * <p>The message is written for the customer who configured the destination, and names the
 * class of address rather than the address: telling somebody their name resolved to
 * {@code 10.0.0.5} confirms the internal range to whoever pointed it there.
 */
public class EgressRefusedException extends RuntimeException {

    public EgressRefusedException(String message) {
        super(message);
    }

    public EgressRefusedException(String message, Throwable cause) {
        super(message, cause);
    }

}
