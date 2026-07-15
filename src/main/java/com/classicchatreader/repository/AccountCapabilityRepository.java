package com.classicchatreader.repository;

import com.classicchatreader.entity.AccountCapabilityEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountCapabilityRepository extends JpaRepository<AccountCapabilityEntity, String> {
    boolean existsByUserIdAndCapabilityAndStatus(String userId, String capability, String status);
}
