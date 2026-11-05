package com.vrsms.server.services;

import com.vrsms.server.models.*;
import com.vrsms.server.repositories.*;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
public class RentalService {

    @Autowired
    private LoanRepository loanRepository;
    @Autowired
    private InventoryItemRepository itemRepository;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private SystemConfigService configService;
    @Autowired
    private CouponRepository couponRepository;

    // ==========================================
    // USE CASE: ISSUE RENTAL
    // ==========================================
    @Transactional
    public Loan issueRental(UUID memberId, UUID itemId, UUID clerkId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new RuntimeException("Member not found"));

        // ---> THE CANCELLATION FAILSAFE <---
        if (!member.isActive()) {
            throw new RuntimeException("Action Blocked: This membership has been cancelled. You cannot issue items to this user.");
        }

        InventoryItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new RuntimeException("Item not found"));
        User clerk = userRepository.findById(clerkId)
                .orElseThrow(() -> new RuntimeException("Clerk not found"));

        long activeCount = loanRepository.countByMember_MemberIdAndStatus(member.getMemberId(), LoanStatus.ACTIVE);

        if (activeCount >= 2) {
            throw new RuntimeException("Rental Blocked: Member already has 2 active rentals (Maximum Limit Reached).");
        }

        if (item.getStatus() != ItemStatus.AVAILABLE) {
            throw new RuntimeException("Item is currently not available for rent.");
        }

        if (member.getCurrentDues().compareTo(BigDecimal.ZERO) > 0) {
            throw new RuntimeException("Rental blocked: Member has outstanding fines.");
        }

        SystemConfig config = configService.getConfig();

        Loan loan = new Loan();
        loan.setMember(member);
        loan.setItem(item);
        loan.setIssuedBy(clerk);

        // --- TIMESTAMPS FIXED TO INDIA STANDARD TIME (IST) ---
        LocalDateTime nowIST = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        loan.setIssueDate(nowIST);
        loan.setDueDate(nowIST.plusDays(config.getMaxRentalDays()));

        loan.setRentAmount(BigDecimal.ZERO); // Rent is calculated at return, not checkout!
        loan.setStatus(LoanStatus.ACTIVE);

        item.setStatus(ItemStatus.ON_LOAN);
        itemRepository.save(item);

        return loanRepository.save(loan);
    }

    // ==========================================
    // USE CASE: PROCESS RETURN & CALCULATE FINES
    // ==========================================
    @Transactional
    public Loan processReturn(UUID loanId, UUID clerkId, String couponCode) {
        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new RuntimeException("Loan record not found"));
        User clerk = userRepository.findById(clerkId)
                .orElseThrow(() -> new RuntimeException("Clerk not found"));

        if (loan.getStatus() != LoanStatus.ACTIVE && loan.getStatus() != LoanStatus.OVERDUE) {
            throw new RuntimeException("This item is already returned or cancelled.");
        }

        // ==========================================
        // DUAL-TIMEZONE FIX (IST CLOCK + DAY MATH)
        // ==========================================
        LocalDateTime rightNow = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        loan.setReturnDate(rightNow);
        loan.setReturnedBy(clerk);
        loan.setStatus(LoanStatus.RETURNED);

        // Convert pure Dates (stripping the hours/minutes) just for the Money Math
        LocalDate todayDate = rightNow.toLocalDate();
        LocalDate issueDateOnly = loan.getIssueDate().toLocalDate();

        long daysKept = ChronoUnit.DAYS.between(issueDateOnly, todayDate);
        if (daysKept < 1) daysKept = 1;

        BigDecimal actualRent = loan.getItem().getDailyRate().multiply(BigDecimal.valueOf(daysKept));

        if (couponCode != null && !couponCode.trim().isEmpty()) {
            Optional<Coupon> promo = couponRepository.findByCodeIgnoreCaseAndActiveTrue(couponCode.trim());

            if (promo.isPresent()) {
                // SANITIZATION: Force discount to stay between 0% and 100%
                double rawDiscount = promo.get().getDiscountPercentage();
                if (rawDiscount > 100.0) rawDiscount = 100.0;
                if (rawDiscount < 0.0) rawDiscount = 0.0;

                double discount = rawDiscount / 100.0;
                BigDecimal discountAmount = actualRent.multiply(BigDecimal.valueOf(discount));
                actualRent = actualRent.subtract(discountAmount);
            } else {
                throw new RuntimeException("Invalid or Expired Coupon Code: " + couponCode.toUpperCase());
            }
        }

        loan.setRentAmount(actualRent);

        // Calculate Late Fines using pure Dates
        BigDecimal totalFine = BigDecimal.ZERO;
        LocalDate dueDateOnly = loan.getDueDate().toLocalDate();

        if (todayDate.isAfter(dueDateOnly)) {
            long daysLate = ChronoUnit.DAYS.between(dueDateOnly, todayDate);
            SystemConfig config = configService.getConfig();
            totalFine = config.getLateFeePerDay().multiply(BigDecimal.valueOf(daysLate));
        }
        loan.setFineAmount(totalFine);

        Member member = loan.getMember();
        BigDecimal currentDues = member.getCurrentDues();

        if (currentDues == null) {
            currentDues = BigDecimal.ZERO;
        }

        BigDecimal totalOwedForThisTransaction = actualRent.add(totalFine);
        member.setCurrentDues(currentDues.add(totalOwedForThisTransaction));
        memberRepository.save(member);

        InventoryItem item = loan.getItem();
        item.setStatus(ItemStatus.AVAILABLE);
        itemRepository.save(item);

        return loanRepository.save(loan);
    }
    // ==========================================
    // USE CASE: FETCH GLOBAL LEDGER TRANSACTIONS
    // ==========================================
    public java.util.List<Loan> getAllRentals() {
        // Automatically fetches every transaction sorted by your database
        return loanRepository.findAll();
    }
}