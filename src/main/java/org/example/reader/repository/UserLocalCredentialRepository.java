package org.example.reader.repository;

import org.example.reader.entity.UserLocalCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserLocalCredentialRepository extends JpaRepository<UserLocalCredentialEntity, String> {

    Optional<UserLocalCredentialEntity> findByUserId(String userId);
}
