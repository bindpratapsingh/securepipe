package com.vrsms.server.controllers;

import com.vrsms.server.models.Loan;
import com.vrsms.server.services.RentalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/rentals")
@CrossOrigin(origins = "*")
public class RentalController {

    @Autowired
    private RentalService rentalService;

    // ==========================================
    // ENDPOINT: ISSUE RENTAL
    // React calls: POST http://localhost:8080/api/rentals/issue
    // ==========================================
    @PostMapping("/issue")
    public ResponseEntity<?> issueRental(@RequestBody RentalRequest request) {
        try {
            // Hand the JSON data straight to your awesome Chunk 3 Service
            Loan newLoan = rentalService.issueRental(request.getMemberId(), request.getItemId(), request.getClerkId());
            return ResponseEntity.ok(newLoan); // Send the completed receipt back to React
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage()); // Send the error (e.g., "Member has fines") to React
        }
    }

    // ==========================================
    // ENDPOINT: PROCESS RETURN
    // React calls: POST http://localhost:8080/api/rentals/return
    // ==========================================
    @PostMapping("/return")
    public ResponseEntity<?> processReturn(@RequestBody ReturnRequest request) {
        try {
            // UPDATED: Passing the 3rd argument (the coupon code) to your service
            Loan updatedLoan = rentalService.processReturn(
                    request.getLoanId(),
                    request.getClerkId(),
                    request.getCouponCode() // <-- THIS IS THE NEW PIECE
            );
            return ResponseEntity.ok(updatedLoan);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ==========================================
    // DATA TRANSFER OBJECTS (DTOs)
    // These classes perfectly match the JSON shape React will send to Java.
    // ==========================================

    public static class RentalRequest {
        private UUID memberId;
        private UUID itemId;
        private UUID clerkId;

        public UUID getMemberId() { return memberId; }
        public void setMemberId(UUID memberId) { this.memberId = memberId; }
        public UUID getItemId() { return itemId; }
        public void setItemId(UUID itemId) { this.itemId = itemId; }
        public UUID getClerkId() { return clerkId; }
        public void setClerkId(UUID clerkId) { this.clerkId = clerkId; }
    }

    public static class ReturnRequest {
        private java.util.UUID loanId;
        private java.util.UUID clerkId;
        private String couponCode; // <-- NEW: EXPECTS COUPON

        public java.util.UUID getLoanId() { return loanId; }
        public void setLoanId(java.util.UUID loanId) { this.loanId = loanId; }
        public java.util.UUID getClerkId() { return clerkId; }
        public void setClerkId(java.util.UUID clerkId) { this.clerkId = clerkId; }
        public String getCouponCode() { return couponCode; }
        public void setCouponCode(String couponCode) { this.couponCode = couponCode; }
    }

    @Autowired
    private com.vrsms.server.repositories.MemberRepository memberRepository;

    @Autowired
    private com.vrsms.server.repositories.LoanRepository loanRepository;

    // React calls: GET /api/rentals/my-active/{userId}
    @GetMapping("/my-active/{userId}")
    public ResponseEntity<?> getMyActiveRentals(@PathVariable java.util.UUID userId) {
        try {
            // 1. Find the Member profile linked to this User ID
            com.vrsms.server.models.Member member = memberRepository.findByUser_UserId(userId)
                    .orElseThrow(() -> new RuntimeException("Member profile not found."));

            // 2. Fetch all their active loans
            java.util.List<com.vrsms.server.models.Loan> activeLoans =
                    loanRepository.findByMember_MemberIdAndStatus(member.getMemberId(), com.vrsms.server.models.LoanStatus.ACTIVE);

            return ResponseEntity.ok(activeLoans);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }


    // ==========================================
    // ENDPOINT: GET ALL TRANSACTIONS (GLOBAL)
    // ==========================================
    @GetMapping("/all")
    public org.springframework.http.ResponseEntity<java.util.List<Loan>> getAllRentals() {
        return org.springframework.http.ResponseEntity.ok(rentalService.getAllRentals());
    }

    public ResponseEntity<?> getAllTransactions() {
        try {
            java.util.List<com.vrsms.server.models.Loan> allLoans = loanRepository.findAll();
            java.util.List<java.util.Map<String, Object>> responseList = new java.util.ArrayList<>();

            for (com.vrsms.server.models.Loan l : allLoans) {
                java.util.Map<String, Object> map = new java.util.HashMap<>();
                map.put("loanId", l.getLoanId());
                map.put("issueDate", l.getIssueDate());
                map.put("returnDate", l.getReturnDate());
                map.put("status", l.getStatus().toString());
                map.put("rentAmount", l.getRentAmount() != null ? l.getRentAmount() : 0);
                map.put("fineAmount", l.getFineAmount() != null ? l.getFineAmount() : 0);
                map.put("itemTitle", l.getItemTitle());

                // Safely grab the member's name
                if (l.getMember() != null && l.getMember().getUser() != null) {
                    map.put("memberName", l.getMember().getUser().getFullName());
                } else {
                    map.put("memberName", "Unknown Member");
                }

                responseList.add(map);
            }

            // Sort by newest first
            responseList.sort((a, b) -> {
                java.time.LocalDate dateA = (java.time.LocalDate) a.get("issueDate");
                java.time.LocalDate dateB = (java.time.LocalDate) b.get("issueDate");
                return dateB.compareTo(dateA);
            });

            return ResponseEntity.ok(responseList);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body("Failed to fetch global transactions.");
        }
    }
}