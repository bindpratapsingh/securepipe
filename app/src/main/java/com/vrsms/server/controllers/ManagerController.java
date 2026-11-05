package com.vrsms.server.controllers;

import com.vrsms.server.models.InventoryItem;
import com.vrsms.server.models.Loan;
import com.vrsms.server.models.SystemConfig;
import com.vrsms.server.repositories.InventoryRepository;
import com.vrsms.server.repositories.LoanRepository;
import com.vrsms.server.repositories.SystemConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/manager")
@CrossOrigin(origins = "*")
public class ManagerController {

    @Autowired private SystemConfigRepository configRepo;
    @Autowired private LoanRepository loanRepo;
    @Autowired private InventoryRepository inventoryRepo;
    @Autowired private com.vrsms.server.repositories.CouponRepository couponRepository;

    // ---> NEW: Required to update member status <---
    @Autowired private com.vrsms.server.repositories.MemberRepository memberRepository;

    // --- 1. SYSTEM CONFIGURATION ---
    @GetMapping("/config")
    public ResponseEntity<SystemConfig> getConfig() {
        return ResponseEntity.ok(configRepo.findById(1).orElse(new SystemConfig()));
    }

    @PostMapping("/config")
    public ResponseEntity<SystemConfig> updateConfig(@RequestBody SystemConfig updatedConfig) {
        updatedConfig.setConfigId(1); // Force it to update the single row
        return ResponseEntity.ok(configRepo.save(updatedConfig));
    }

    // --- 2. PROFIT & LOSS ANALYTICS ---
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getFinancialStats() {
        List<Loan> allLoans = loanRepo.findAll();
        List<InventoryItem> allItems = inventoryRepo.findAll();

        // Calculate Total Revenue (Rent paid + Fines paid)
        double totalRevenue = allLoans.stream().mapToDouble(l -> {
            double rent = l.getRentAmount() != null ? l.getRentAmount().doubleValue() : 0.0;
            double fines = l.getFineAmount() != null ? l.getFineAmount().doubleValue() : 0.0;
            return rent + fines;
        }).sum();

        // Calculate Total Costs (Sum of all movie purchase prices)
        double totalCost = allItems.stream()
                .mapToDouble(i -> i.getPurchasePrice() != null ? i.getPurchasePrice().doubleValue() : 0.0)
                .sum();

        double profit = totalRevenue - totalCost;

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRevenue", totalRevenue);
        stats.put("totalCost", totalCost);
        stats.put("netProfit", profit);
        stats.put("totalInventoryCount", allItems.size());
        stats.put("totalLoansCount", allLoans.size());

        // Attach the raw lists so the React dashboard can display the ledger!
        stats.put("inventory", allItems);
        stats.put("loans", allLoans);

        return ResponseEntity.ok(stats);

    }

    @GetMapping("/coupons/all")
    public ResponseEntity<?> getAllCoupons() {
        return ResponseEntity.ok(couponRepository.findAll());
    }

    @PostMapping("/coupons/add")
    public ResponseEntity<?> addCoupon(@RequestBody com.vrsms.server.models.Coupon coupon) {
        if (coupon.getDiscountPercentage() < 0 || coupon.getDiscountPercentage() > 100) {
            return ResponseEntity.badRequest().body("Error: Discount percentage must be between 0 and 100.");
        }
        coupon.setCode(coupon.getCode().toUpperCase());
        return ResponseEntity.ok(couponRepository.save(coupon));
    }

    @DeleteMapping("/coupons/{id}")
    public ResponseEntity<?> deleteCoupon(@PathVariable java.util.UUID id) {
        couponRepository.deleteById(id);
        return ResponseEntity.ok("Coupon deleted");
    }

    // ---> NEW: THE KILL SWITCH ENDPOINT <---
    @PutMapping("/members/{memberId}/toggle-status")
    public ResponseEntity<?> toggleMemberStatus(@PathVariable java.util.UUID memberId) {
        try {
            com.vrsms.server.models.Member member = memberRepository.findById(memberId)
                    .orElseThrow(() -> new RuntimeException("Member not found"));

            // Flips true to false, or false to true
            member.setActive(!member.isActive());
            memberRepository.save(member);

            String status = member.isActive() ? "Restored" : "Cancelled";
            return ResponseEntity.ok("Membership successfully " + status + "!");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}