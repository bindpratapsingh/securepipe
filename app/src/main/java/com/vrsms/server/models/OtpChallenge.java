package com.vrsms.server.models;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "otp_challenges")
public class OtpChallenge {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID challengeId;

    @Column(nullable = false)
    private String phoneNumber;

    @Column(nullable = false)
    private String otpCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OtpPurpose purpose;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    // --- THE RESTORED AUTH SERVICE FIELDS ---
    @Column(nullable = false)
    private short attempts = 0;

    @Column(nullable = false)
    private boolean isActive = true;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    // --- STANDARD GETTERS & SETTERS ---
    public UUID getChallengeId() { return challengeId; }
    public void setChallengeId(UUID challengeId) { this.challengeId = challengeId; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getOtpCode() { return otpCode; }
    public void setOtpCode(String otpCode) { this.otpCode = otpCode; }

    public OtpPurpose getPurpose() { return purpose; }
    public void setPurpose(OtpPurpose purpose) { this.purpose = purpose; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public short getAttempts() { return attempts; }
    public void setAttempts(short attempts) { this.attempts = attempts; }

    public boolean getIsActive() { return isActive; }
    public void setIsActive(boolean isActive) { this.isActive = isActive; }

    public LocalDateTime getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(LocalDateTime verifiedAt) { this.verifiedAt = verifiedAt; }

    // --- ALIAS METHODS (Fixes the setPhone error in AuthService) ---
    public void setPhone(String phone) { this.phoneNumber = phone; }
    public String getPhone() { return this.phoneNumber; }
}