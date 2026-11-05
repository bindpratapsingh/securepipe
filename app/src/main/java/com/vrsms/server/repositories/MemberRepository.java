package com.vrsms.server.repositories;

import com.vrsms.server.models.Member;
import com.vrsms.server.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MemberRepository extends JpaRepository<Member, UUID> {
    Optional<Member> findByUser(User user);
    Optional<Member> findByUser_UserId(UUID userId);
}