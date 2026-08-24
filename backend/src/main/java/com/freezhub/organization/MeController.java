package com.freezhub.organization;

import com.freezhub.shared.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    @GetMapping("/api/me")
    public AuthenticatedUser me(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        return authenticatedUser;
    }

}
