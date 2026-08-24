package com.freezhub.organization;

import com.freezhub.shared.security.IdentityProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InviteService {

    private final UserRepository userRepository;
    private final IdentityProvider identityProvider;

    public InviteService(UserRepository userRepository, IdentityProvider identityProvider) {
        this.userRepository = userRepository;
        this.identityProvider = identityProvider;
    }

    @Transactional
    public User invite(Long organizationId, String email, UserRole requestedRole) {
        if (userRepository.existsByOrganizationIdAndEmail(organizationId, email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User already exists in this organization");
        }

        UserRole role = requestedRole != null ? requestedRole : UserRole.MEMBER;
        String cognitoSubject = identityProvider.createUser(email);

        return userRepository.save(new User(organizationId, cognitoSubject, email, role));
    }

}
