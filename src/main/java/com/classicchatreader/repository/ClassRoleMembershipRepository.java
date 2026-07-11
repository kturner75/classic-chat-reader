package com.classicchatreader.repository;

import com.classicchatreader.entity.ClassRoleMembershipEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClassRoleMembershipRepository extends JpaRepository<ClassRoleMembershipEntity, String> {
    List<ClassRoleMembershipEntity> findByUserIdAndStatus(String userId, String status);
    Optional<ClassRoleMembershipEntity> findByTermIdAndUserIdAndRole(String termId, String userId, String role);
    List<ClassRoleMembershipEntity> findByTermIdAndStatus(String termId, String status);
    boolean existsByTermIdAndUserIdAndStatus(String termId, String userId, String status);
}
