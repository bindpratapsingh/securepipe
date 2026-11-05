package com.vrsms.server.controllers;

import com.vrsms.server.models.OtpPurpose;
import com.vrsms.server.models.User;
import com.vrsms.server.services.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    @Autowired
    private AuthService authService;

    @PostMapping("/request-otp")
    public ResponseEntity<?> requestOtp(@RequestBody OtpRequest request) {
        try {
            String message = authService.requestOtp(request.getPhone(), request.getPurpose());
            return ResponseEntity.ok(message);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<?> verifyOtp(@RequestBody VerifyRequest request) {
        try {
            User user = authService.verifyOtp(request.getPhone(), request.getPurpose(), request.getCode());
            return ResponseEntity.ok(user);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // React calls: POST http://localhost:8080/api/auth/login-staff
    @PostMapping("/login-staff")
    public ResponseEntity<?> loginStaff(@RequestBody StaffLoginRequest request) {
        try {
            User user = authService.loginStaff(request.getPhone(), request.getPassword());
            return ResponseEntity.ok(user);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // --- DTO for Staff Login ---
    public static class StaffLoginRequest {
        private String phone;
        private String password;
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    // --- DTOs (Data Transfer Objects) ---
    public static class OtpRequest {
        private String phone;
        private OtpPurpose purpose;
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public OtpPurpose getPurpose() { return purpose; }
        public void setPurpose(OtpPurpose purpose) { this.purpose = purpose; }
    }

    public static class VerifyRequest {
        private String phone;
        private OtpPurpose purpose;
        private String code;
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public OtpPurpose getPurpose() { return purpose; }
        public void setPurpose(OtpPurpose purpose) { this.purpose = purpose; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
    }
}