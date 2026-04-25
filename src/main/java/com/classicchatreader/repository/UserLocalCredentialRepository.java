package com.classicchatreader.repository;

import com.classicchatreader.entity.UserLocalCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserLocalCredentialRepository extends JpaRepository<UserLocalCredentialEntity, String> {

    Optional<UserLocalCredentialEntity> findByUserId(String userId);
}
