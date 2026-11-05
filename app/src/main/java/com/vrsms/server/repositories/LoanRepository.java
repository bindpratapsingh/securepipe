package com.vrsms.server.repositories;

import com.vrsms.server.models.Loan;
import com.vrsms.server.models.LoanStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LoanRepository extends JpaRepository<Loan, UUID> {

    // Added .member. to navigate the Java object correctly!
    @Query("SELECT l FROM Loan l WHERE l.member.memberId = :memberId ORDER BY l.issueDate DESC")
    List<Loan> findByMemberIdOrderByIssueDateDesc(@Param("memberId") UUID memberId);

    // Added .member. here as well to keep it crash-proof!
    @Query("SELECT l FROM Loan l WHERE l.member.memberId = :memberId AND l.status = :status")
    List<Loan> findByMember_MemberIdAndStatus(@Param("memberId") UUID memberId, @Param("status") LoanStatus status);

    long countByMember_MemberIdAndStatus(UUID memberId, LoanStatus status);
}