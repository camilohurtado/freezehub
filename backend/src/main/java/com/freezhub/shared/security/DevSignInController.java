package com.freezhub.shared.security;

import com.freezhub.organization.User;
import com.freezhub.organization.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Local development sign-in (FZ-035). Issues the same locally-signed token the tests use,
 * so the frontend can authenticate before a real Cognito user pool exists (FZ-063).
 *
 * <p><strong>Cannot exist outside development.</strong> Three independent things stop it:
 * this bean is {@code @Profile("local")}; the {@link JwtEncoder} it depends on is only
 * defined under that profile; and {@code DevSignInSecurityConfig} - which is what makes
 * the path reachable without a token - is profile-scoped too. No deployed environment
 * activates the {@code local} profile.
 *
 * <p>It mints tokens only for users that already exist. It is a sign-in shortcut, not a
 * way to conjure identities.
 */
@RestController
@RequestMapping("/api/dev")
@Profile("local")
public class DevSignInController {

    private final UserRepository userRepository;
    private final JwtEncoder jwtEncoder;

    public DevSignInController(UserRepository userRepository, JwtEncoder jwtEncoder) {
        this.userRepository = userRepository;
        this.jwtEncoder = jwtEncoder;
    }

    @PostMapping("/token")
    public DevTokenResponse token(@Valid @RequestBody DevTokenRequest request) {
        List<User> matches = userRepository.findAllByEmail(request.email());

        if (matches.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No user with that email; create one before signing in");
        }
        if (matches.size() > 1) {
            // Email is unique per organization, not globally - refuse rather than guess.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That email exists in more than one organization; cannot choose between them");
        }

        User user = matches.getFirst();
        return new DevTokenResponse(
                LocalTokenIssuer.issue(jwtEncoder, user.getExternalSubject()),
                user.getId(),
                user.getOrganizationId(),
                user.getEmail(),
                user.getRole());
    }

    public record DevTokenRequest(@NotBlank String email) {
    }

    public record DevTokenResponse(String token, Long userId, Long organizationId, String email,
                                   com.freezhub.organization.UserRole role) {
    }

}
