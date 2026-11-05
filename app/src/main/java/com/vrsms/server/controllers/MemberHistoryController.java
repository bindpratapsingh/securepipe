package com.vrsms.server.controllers;

import com.vrsms.server.models.Loan;
import com.vrsms.server.models.Member;
import com.vrsms.server.repositories.LoanRepository;
import com.vrsms.server.repositories.MemberRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/history")
@CrossOrigin(origins = "*")
public class MemberHistoryController {

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private MemberRepository memberRepository;

    @GetMapping("/{userId}")
    public ResponseEntity<?> getMyHistory(@PathVariable UUID userId) {
        try {
            Member member = memberRepository.findByUser_UserId(userId)
                    .orElseThrow(() -> new RuntimeException("Member profile not found."));

            // Fetches all loans, newest first!
            List<Loan> history = loanRepository.findByMemberIdOrderByIssueDateDesc(member.getMemberId());
            return ResponseEntity.ok(history);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}