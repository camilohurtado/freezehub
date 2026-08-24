package com.freezhub.organization;

public record InviteUserResponse(Long userId, String email, UserRole role) {

    static InviteUserResponse from(User user) {
        return new InviteUserResponse(user.getId(), user.getEmail(), user.getRole());
    }

}
