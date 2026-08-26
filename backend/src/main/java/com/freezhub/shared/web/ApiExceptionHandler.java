package com.freezhub.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Returns the reason attached to a {@link ResponseStatusException} so the client can say
 * what actually went wrong.
 *
 * <p>Spring omits {@code message} from its default error body unless
 * {@code server.error.include-message} is enabled, which would also expose the text of
 * *unexpected* exceptions — a 500 would start leaking internals. This is narrower on
 * purpose: only reasons the application deliberately wrote are returned, and anything
 * unhandled still falls through to Spring's default handling with no message.
 *
 * <p>Without this every carefully worded conflict — "a team with this name already
 * exists", "referenced by one or more change restrictions" — reaches the UI as nothing
 * more than "409".
 *
 * <p>A complete error contract is `FZ-061`; this is the minimum that makes the messages
 * the API already produces actually usable.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiErrorBody> handleResponseStatusException(ResponseStatusException exception,
                                                              HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        String error = status != null ? status.getReasonPhrase() : "Error";

        return ResponseEntity.status(exception.getStatusCode())
                .body(new ApiErrorBody(
                        Instant.now(),
                        exception.getStatusCode().value(),
                        error,
                        exception.getReason(),
                        request.getRequestURI()));
    }

    /** Mirrors the shape of Spring's default error body, plus the message. */
    public record ApiErrorBody(Instant timestamp, int status, String error, String message, String path) {
    }

}
