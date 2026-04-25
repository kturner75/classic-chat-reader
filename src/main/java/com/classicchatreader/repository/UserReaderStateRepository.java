package com.classicchatreader.repository;

import com.classicchatreader.entity.UserReaderStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserReaderStateRepository extends JpaRepository<UserReaderStateEntity, String> {
}
