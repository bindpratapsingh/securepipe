package com.vrsms.server.controllers;

import com.twilio.rest.verify.v2.service.Verification;
import com.twilio.rest.verify.v2.service.VerificationCheck;
import com.vrsms.server.models.Member;
import com.vrsms.server.models.User;
import com.vrsms.server.models.OtpChallenge;
import com.vrsms.server.models.OtpPurpose;
import com.vrsms.server.repositories.MemberRepository;
import com.vrsms.server.repositories.UserRepository;
import com.vrsms.server.repositories.OtpChallengeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

@RestController
@RequestMapping("/api/staff")
@CrossOrigin(origins = "*")
public class StaffController {

    @Autowired private UserRepository userRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private OtpChallengeRepository otpChallengeRepository;

    // Added to access Twilio Verify Service SID
    @Value("${twilio.verify-service-sid}")
    private String twilioVerifyServiceSid;

    // ==========================================
    // HYBRID OTP GENERATOR (Twilio + Fallback)
    // ==========================================
    @PostMapping("/send-registration-otp")
    public ResponseEntity<?> sendRegistrationOtp(@RequestParam String phone) {

        // 1. STRICT VALIDATION: Must be +91, followed by exactly 10 digits starting with 6, 7, 8, or 9.
        // This completely blocks "0000000000" or fake formats.
        if (!phone.matches("^\\+91[6-9][0-9]{9}$")) {
            return ResponseEntity.badRequest().body("Invalid phone number format. Must be a valid 10-digit Indian mobile number.");
        }

        // 2. EARLY DUPLICATE CHECK: Don't waste an OTP if they are already registered!
        java.util.Optional<User> existingUser = userRepository.findByPhone(phone);
        if (existingUser.isPresent() && memberRepository.findByUser_UserId(existingUser.get().getUserId()).isPresent()) {
            return ResponseEntity.badRequest().body("Error: A member with this phone number is already registered in the system.");
        }

        // 3. Generate Local Fallback OTP
        String fallbackOtp = String.format("%06d", new java.util.Random().nextInt(999999));

        // 4. Save to Database
        OtpChallenge challenge = new OtpChallenge();
        challenge.setPhoneNumber(phone);
        challenge.setOtpCode(fallbackOtp);
        challenge.setPurpose(OtpPurpose.REGISTRATION);
        challenge.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        otpChallengeRepository.save(challenge);

        // 5. Attempt REAL Twilio SMS
        try {
            Verification verification = Verification.creator(
                    twilioVerifyServiceSid,
                    phone,
                    "sms"
            ).create();
            System.out.println("Twilio SMS Status (Registration): " + verification.getStatus());

        } catch (Exception e) {
            // 6. Graceful Degradation: Print Fallback if Twilio fails
            System.out.println("\n========================================");
            System.out.println("🚨 TWILIO SMS FAILED (Registration) 🚨");
            System.out.println("Twilio Error: " + e.getMessage());
            System.out.println("FALLBACK REGISTRATION OTP FOR " + phone + ": " + fallbackOtp);
            System.out.println("========================================\n");
        }

        return ResponseEntity.ok("OTP Generated Successfully! (Check phone or Java Console)");
    }

    // ==========================================
    // EXPLICIT OTP VERIFICATION STEP
    // ==========================================
    @PostMapping("/verify-registration-otp")
    public ResponseEntity<?> verifyRegistrationOtp(@RequestParam String phone, @RequestParam String otp) {
        try {
            String fullPhone = phone.startsWith("+91") ? phone : "+91" + phone;

            OtpChallenge challenge = otpChallengeRepository
                    .findTopByPhoneNumberAndPurposeOrderByCreatedAtDesc(fullPhone, OtpPurpose.REGISTRATION)
                    .orElseThrow(() -> new RuntimeException("No OTP requested for this number."));

            if (challenge.getExpiresAt().isBefore(LocalDateTime.now())) {
                return ResponseEntity.badRequest().body("OTP has expired. Please click 'Send OTP' to request a new one.");
            }

            if (!challenge.getOtpCode().equals(otp)) {
                return ResponseEntity.badRequest().body("Invalid OTP Code! Please try again.");
            }

            return ResponseEntity.ok("OTP Verified Successfully!");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/lookup-member")
    public ResponseEntity<?> lookupMemberByPhone(@RequestParam String phone) {
        try {
            User user = userRepository.findByPhone(phone)
                    .orElseThrow(() -> new RuntimeException("No account found with this phone number."));

            Member member = memberRepository.findByUser_UserId(user.getUserId())
                    .orElseThrow(() -> new RuntimeException("This user is not registered as a Member."));

            Map<String, Object> response = new HashMap<>();
            response.put("memberId", member.getMemberId());
            response.put("fullName", user.getFullName());
            response.put("userId", user.getUserId());
            response.put("photoUrl", member.getPhotoUrl());
            response.put("currentDues", member.getCurrentDues() != null ? member.getCurrentDues() : java.math.BigDecimal.ZERO);
            response.put("isActive", member.isActive());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ==========================================
    // CLERK USE CASE: RESTORE CANCELLED MEMBER
    // ==========================================
    @PutMapping("/members/{userId}/restore")
    public ResponseEntity<?> restoreCancelledMember(@PathVariable java.util.UUID userId) {
        try {
            com.vrsms.server.models.User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));
            com.vrsms.server.models.Member member = memberRepository.findByUser(user)
                    .orElseThrow(() -> new RuntimeException("Member profile not found"));

            if (member.isActive()) {
                return ResponseEntity.badRequest().body("This member is already active.");
            }

            // 1. Reactivate the account
            member.setActive(true);

            // 2. Record the fresh ₹1000 deposit
            member.setDepositPaid(java.math.BigDecimal.valueOf(1000.00));

            memberRepository.save(member);

            return ResponseEntity.ok("Membership restored successfully! ₹1000 deposit recorded.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Failed to restore member: " + e.getMessage());
        }
    }

    @GetMapping("/members/all")
    public ResponseEntity<?> getAllMembers() {
        try {
            List<Member> allMembers = memberRepository.findAll();
            List<Map<String, Object>> responseList = new ArrayList<>();

            for (Member m : allMembers) {
                User u = m.getUser();
                if (u != null) {
                    Map<String, Object> memberData = new HashMap<>();
                    memberData.put("userId", u.getUserId());
                    memberData.put("memberId", m.getMemberId());
                    memberData.put("fullName", u.getFullName());
                    memberData.put("phone", u.getPhone());
                    memberData.put("address", m.getAddress());
                    memberData.put("photoUrl", m.getPhotoUrl());
                    memberData.put("depositPaid", m.getDepositPaid());
                    memberData.put("currentDues", m.getCurrentDues() != null ? m.getCurrentDues() : java.math.BigDecimal.ZERO);

                    memberData.put("isActive", m.isActive());

                    responseList.add(memberData);
                }
            }
            return ResponseEntity.ok(responseList);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Failed to fetch members: " + e.getMessage());
        }
    }

    @PostMapping("/register-member")
    public ResponseEntity<?> registerNewMember(@RequestBody RegistrationRequest request) {
        try {
            String fullPhone = request.getPhone();
            if (!fullPhone.startsWith("+91")) {
                fullPhone = "+91" + fullPhone;
            }

            OtpChallenge challenge = otpChallengeRepository
                    .findTopByPhoneNumberAndPurposeOrderByCreatedAtDesc(fullPhone, OtpPurpose.REGISTRATION)
                    .orElseThrow(() -> new RuntimeException("No OTP requested for this number. Click 'Send OTP' first."));

            if (challenge.getExpiresAt().isBefore(LocalDateTime.now())) {
                return ResponseEntity.badRequest().body("OTP has expired. Please click 'Send OTP' to request a new one.");
            }

            boolean isApproved = false;

            // 1. Check Local Fallback First
            if (challenge.getOtpCode().equals(request.getOtp())) {
                isApproved = true;
            } else {
                // 2. Check Twilio if local doesn't match
                try {
                    VerificationCheck verificationCheck = VerificationCheck.creator(twilioVerifyServiceSid)
                            .setTo(fullPhone)
                            .setCode(request.getOtp())
                            .create();

                    if ("approved".equals(verificationCheck.getStatus())) {
                        isApproved = true;
                    }
                } catch (Exception e) {
                    System.out.println("Twilio registration check failed: " + e.getMessage());
                }
            }

            if (!isApproved) {
                return ResponseEntity.badRequest().body("Invalid OTP Code! Please try again.");
            }

            // 1. Create or find the User account
            User user = userRepository.findByPhone(fullPhone).orElse(new User());
            if (user.getUserId() == null) {
                user.setPhone(fullPhone);
                user.setFullName(request.getFullName());
                user.setRole(com.vrsms.server.models.UserRole.MEMBER);
                user = userRepository.save(user);
            } else {
                if (memberRepository.findByUser_UserId(user.getUserId()).isPresent()) {
                    return ResponseEntity.badRequest().body("A member with this phone number already exists.");
                }
            }

            // 2. Create the Member Profile
            Member newMember = new Member();
            newMember.setUser(user);
            newMember.setAddress(request.getAddress());
            newMember.setPhotoUrl(request.getPhotoUrl());

            if (request.getDepositPaid() != null && request.getDepositPaid()) {
                newMember.setDepositPaid(java.math.BigDecimal.valueOf(1000.00));
            } else {
                newMember.setDepositPaid(java.math.BigDecimal.ZERO);
            }

            memberRepository.save(newMember);
            otpChallengeRepository.delete(challenge);

            return ResponseEntity.ok("Member successfully registered and verified!");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Registration failed: " + e.getMessage());
        }
    }

    public static class RegistrationRequest {
        private String fullName;
        private String phone;
        private String address;
        private String photoUrl;
        private Boolean depositPaid;
        private String otp;

        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getPhotoUrl() { return photoUrl; }
        public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }
        public Boolean getDepositPaid() { return depositPaid; }
        public void setDepositPaid(Boolean depositPaid) { this.depositPaid = depositPaid; }
        public String getOtp() { return otp; }
        public void setOtp(String otp) { this.otp = otp; }
    }

    @PutMapping("/members/edit/{userId}")
    public ResponseEntity<?> updateMemberProfile(@PathVariable java.util.UUID userId, @RequestBody UpdateMemberRequest request) {
        try {
            com.vrsms.server.models.User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            com.vrsms.server.models.Member member = memberRepository.findByUser(user)
                    .orElseThrow(() -> new RuntimeException("Member profile not found"));

            user.setFullName(request.getFullName());
            user.setPhone(request.getPhone());
            userRepository.save(user);

            member.setAddress(request.getAddress());
            if (request.getPhotoUrl() != null && !request.getPhotoUrl().isEmpty()) {
                member.setPhotoUrl(request.getPhotoUrl());
            }
            memberRepository.save(member);

            return ResponseEntity.ok("Member profile updated successfully!");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body("Failed to update member: " + e.getMessage());
        }
    }

    public static class UpdateMemberRequest {
        private String fullName;
        private String phone;
        private String address;
        private String photoUrl;

        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getPhotoUrl() { return photoUrl; }
        public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }
    }

    @PostMapping("/members/{userId}/clear-dues")
    public ResponseEntity<?> clearMemberDues(@PathVariable java.util.UUID userId) {
        try {
            com.vrsms.server.models.User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            com.vrsms.server.models.Member member = memberRepository.findByUser(user)
                    .orElseThrow(() -> new RuntimeException("Member profile not found"));

            member.setCurrentDues(java.math.BigDecimal.ZERO);
            memberRepository.save(member);

            return ResponseEntity.ok("Member's outstanding dues have been cleared!");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Failed to clear dues: " + e.getMessage());
        }
    }
}