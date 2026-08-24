package com.freezhub.organization;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByCognitoSubject(String cognitoSubject);

}
