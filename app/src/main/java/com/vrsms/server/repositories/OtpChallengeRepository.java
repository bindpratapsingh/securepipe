package com.vrsms.server.repositories;

import com.vrsms.server.models.OtpChallenge;
import com.vrsms.server.models.OtpPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, UUID> {

    // The method used by the new Staff Registration Flow
    Optional<OtpChallenge> findTopByPhoneNumberAndPurposeOrderByCreatedAtDesc(String phoneNumber, OtpPurpose purpose);

    // The restored method used by AuthService (Mapped manually so AuthService doesn't crash!)
    @Query("SELECT o FROM OtpChallenge o WHERE o.phoneNumber = :phone AND o.purpose = :purpose AND o.isActive = true")
    List<OtpChallenge> findByPhoneAndPurposeAndIsActiveTrue(@Param("phone") String phone, @Param("purpose") OtpPurpose purpose);

}