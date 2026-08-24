package com.freezhub.organization;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record InviteUserRequest(
        @NotBlank @Email String email,
        UserRole role
) {
}
