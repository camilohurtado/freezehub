package com.freezhub.demo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Book a demo" (FZ-083).
 *
 * <p>Unauthenticated, and rate limited by {@code FZ-087} — which is why that story had to
 * come first ({@code OI-11}).
 *
 * <p><strong>There is no way to read demo requests through this API.</strong> Not for the
 * requester, and not for a signed-in customer either: the table sits outside the tenant
 * boundary because a lead belongs to no organization, so there is no organization to scope
 * a read to and no safe way to offer one. Operators read it directly ({@code D-23}).
 */
@RestController
@RequestMapping("/api/demo-requests")
public class DemoRequestController {

    private final DemoRequestService demoRequests;

    public DemoRequestController(DemoRequestService demoRequests) {
        this.demoRequests = demoRequests;
    }

    /**
     * Records the request.
     *
     * <p>{@code 202} rather than {@code 201}: nothing is created that the caller can go
     * and look at, and what happens next is a person getting in touch. The body carries no
     * identifier for the same reason — an id would be a handle to something with no
     * endpoint behind it.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Acknowledgement request(@Valid @RequestBody DemoRequestBody body) {
        demoRequests.record(body.name().trim(), body.email().trim(), body.company().trim(),
                trimToNull(body.teamSize()), trimToNull(body.message()), trimToNull(body.source()),
                Instant.now());
        return new Acknowledgement("Thanks — we'll be in touch shortly.");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Lengths are capped on every field.
     *
     * <p>This is the first endpoint anyone can post to without a credential, so the size of
     * what it will accept is part of its security, not a formatting preference.
     */
    public record DemoRequestBody(
            @NotBlank @Size(max = 255) String name,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 255) String company,
            @Size(max = 32) String teamSize,
            @Size(max = DemoRequest.MAX_MESSAGE_LENGTH) String message,
            @Size(max = 255) String source
    ) {
    }

    /** Deliberately says nothing about what was stored. */
    public record Acknowledgement(String message) {
    }
}
