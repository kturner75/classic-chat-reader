package org.example.reader.repository;

import org.example.reader.entity.UserAuthIdentityEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserAuthIdentityRepository extends JpaRepository<UserAuthIdentityEntity, String> {

    Optional<UserAuthIdentityEntity> findByProviderAndProviderSubject(String provider, String providerSubject);
}
