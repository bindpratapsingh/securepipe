package com.vrsms.server.services;

import com.vrsms.server.models.Coupon;
import com.vrsms.server.models.InventoryItem;
import com.vrsms.server.models.ItemStatus;
import com.vrsms.server.models.Loan;
import com.vrsms.server.models.LoanStatus;
import com.vrsms.server.models.Member;
import com.vrsms.server.models.SystemConfig;
import com.vrsms.server.models.User;
import com.vrsms.server.repositories.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/**
 * Unit tests for the rental business rules. Pure Mockito - no Spring context
 * and no database, which is what lets this suite run on a CI agent.
 *
 * Two things to know when reading these tests:
 *
 * MockitoExtension defaults to strict stubs, so each test stubs only the
 * repositories its own code path actually reaches. A stub that is never used
 * fails the test. That is why the "blocked" tests stub so little: issueRental
 * throws before it gets as far as the config service.
 *
 * RentalService takes its clock from Asia/Kolkata, not the system default, so
 * the fixtures here do the same. Building a fixture from the system clock
 * would make the day arithmetic wrong whenever CI runs in UTC.
 */
@ExtendWith(MockitoExtension.class)
public class RentalServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Mock private LoanRepository loanRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private InventoryItemRepository itemRepository;
    @Mock private UserRepository userRepository;
    @Mock private SystemConfigService configService;
    @Mock private CouponRepository couponRepository;

    @InjectMocks
    private RentalService rentalService;

    // ==========================================
    // USE CASE: ISSUE RENTAL
    // ==========================================

    @Test
    public void testIssueRental_BlocksWhenLimitReached() {
        // 1. SETUP: Create fake entities
        UUID fakeMemberId = UUID.randomUUID();
        Member fakeMember = new Member();
        fakeMember.setMemberId(fakeMemberId);

        InventoryItem fakeItem = new InventoryItem();
        User fakeClerk = new User();

        // 2. MOCK REPOSITORIES: Pretend the database finds them all
        when(memberRepository.findById(fakeMemberId)).thenReturn(Optional.of(fakeMember));
        when(itemRepository.findById(any())).thenReturn(Optional.of(fakeItem));
        when(userRepository.findById(any())).thenReturn(Optional.of(fakeClerk));

        // 3. MOCK BUSINESS RULE: Tell the database this member already has 2 active movies
        when(loanRepository.countByMember_MemberIdAndStatus(fakeMemberId, LoanStatus.ACTIVE)).thenReturn(2L);

        // 4. EXECUTE: Try to issue a 3rd movie, expect a crash
        Exception exception = assertThrows(RuntimeException.class, () -> {
            rentalService.issueRental(fakeMemberId, UUID.randomUUID(), UUID.randomUUID());
        });

        // 5. VERIFY: Make sure the crash message matches our exact security rule
        assertEquals("Rental Blocked: Member already has 2 active rentals (Maximum Limit Reached).", exception.getMessage());
    }

    @Test
    @DisplayName("issueRental: the happy path marks the item on loan and dates the loan from the config")
    public void testIssueRental_SucceedsAndSetsDueDateFromConfig() {
        UUID memberId = UUID.randomUUID();
        Member member = new Member();
        member.setMemberId(memberId);

        InventoryItem item = new InventoryItem();   // defaults to AVAILABLE
        User clerk = new User();

        SystemConfig config = new SystemConfig();
        config.setMaxRentalDays(7);

        when(memberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(itemRepository.findById(any())).thenReturn(Optional.of(item));
        when(userRepository.findById(any())).thenReturn(Optional.of(clerk));
        when(loanRepository.countByMember_MemberIdAndStatus(memberId, LoanStatus.ACTIVE)).thenReturn(0L);
        when(configService.getConfig()).thenReturn(config);
        when(loanRepository.save(any(Loan.class))).thenAnswer(call -> call.getArgument(0));

        Loan loan = rentalService.issueRental(memberId, UUID.randomUUID(), UUID.randomUUID());

        assertEquals(LoanStatus.ACTIVE, loan.getStatus());
        // The copy in inventory has to come off the shelf, or it could be
        // issued to a second member.
        assertEquals(ItemStatus.ON_LOAN, item.getStatus());
        // Rent is charged at return, not at checkout.
        assertEquals(0, BigDecimal.ZERO.compareTo(loan.getRentAmount()));
        // Compared against the loan's own issue date rather than the test's
        // clock, so this cannot fail on a midnight boundary.
        assertEquals(loan.getIssueDate().plusDays(7), loan.getDueDate());
    }

    @Test
    @DisplayName("issueRental: a cancelled membership is refused before anything else is checked")
    public void testIssueRental_BlocksCancelledMembership() {
        UUID memberId = UUID.randomUUID();
        Member member = new Member();
        member.setMemberId(memberId);
        member.setActive(false);

        when(memberRepository.findById(memberId)).thenReturn(Optional.of(member));

        Exception exception = assertThrows(RuntimeException.class, () ->
                rentalService.issueRental(memberId, UUID.randomUUID(), UUID.randomUUID()));

        assertEquals("Action Blocked: This membership has been cancelled. "
                + "You cannot issue items to this user.", exception.getMessage());
    }

    @Test
    @DisplayName("issueRental: an item already on loan cannot be issued again")
    public void testIssueRental_BlocksUnavailableItem() {
        UUID memberId = UUID.randomUUID();
        Member member = new Member();
        member.setMemberId(memberId);

        InventoryItem item = new InventoryItem();
        item.setStatus(ItemStatus.ON_LOAN);

        when(memberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(itemRepository.findById(any())).thenReturn(Optional.of(item));
        when(userRepository.findById(any())).thenReturn(Optional.of(new User()));
        when(loanRepository.countByMember_MemberIdAndStatus(memberId, LoanStatus.ACTIVE)).thenReturn(0L);

        Exception exception = assertThrows(RuntimeException.class, () ->
                rentalService.issueRental(memberId, UUID.randomUUID(), UUID.randomUUID()));

        assertEquals("Item is currently not available for rent.", exception.getMessage());
    }

    @Test
    @DisplayName("issueRental: a member with outstanding fines is refused")
    public void testIssueRental_BlocksMemberWithOutstandingDues() {
        UUID memberId = UUID.randomUUID();
        Member member = new Member();
        member.setMemberId(memberId);
        member.setCurrentDues(new BigDecimal("50.00"));

        when(memberRepository.findById(memberId)).thenReturn(Optional.of(member));
        when(itemRepository.findById(any())).thenReturn(Optional.of(new InventoryItem()));
        when(userRepository.findById(any())).thenReturn(Optional.of(new User()));
        when(loanRepository.countByMember_MemberIdAndStatus(memberId, LoanStatus.ACTIVE)).thenReturn(0L);

        Exception exception = assertThrows(RuntimeException.class, () ->
                rentalService.issueRental(memberId, UUID.randomUUID(), UUID.randomUUID()));

        assertEquals("Rental blocked: Member has outstanding fines.", exception.getMessage());
    }

    // ==========================================
    // USE CASE: PROCESS RETURN & CALCULATE FINES
    // ==========================================

    @Test
    @DisplayName("processReturn: a late return is charged rent for days kept plus a per-day fine")
    public void testProcessReturn_ChargesRentAndLateFine() {
        LocalDateTime now = LocalDateTime.now(IST);
        // Kept 10 days against a due date 3 days ago.
        Loan loan = activeLoan(now.minusDays(10), now.minusDays(3), new BigDecimal("20"), BigDecimal.ZERO);

        SystemConfig config = new SystemConfig();
        config.setLateFeePerDay(new BigDecimal("5"));

        when(loanRepository.findById(loan.getLoanId())).thenReturn(Optional.of(loan));
        when(userRepository.findById(any())).thenReturn(Optional.of(new User()));
        when(configService.getConfig()).thenReturn(config);
        when(loanRepository.save(any(Loan.class))).thenAnswer(call -> call.getArgument(0));

        Loan returned = rentalService.processReturn(loan.getLoanId(), UUID.randomUUID(), null);

        assertEquals(LoanStatus.RETURNED, returned.getStatus());
        assertEquals(0, new BigDecimal("200").compareTo(returned.getRentAmount()), "10 days at 20/day");
        assertEquals(0, new BigDecimal("15").compareTo(returned.getFineAmount()), "3 days late at 5/day");
        // Rent and fine both land on the member's account.
        assertEquals(0, new BigDecimal("215").compareTo(returned.getMember().getCurrentDues()));
        // And the copy goes back on the shelf.
        assertEquals(ItemStatus.AVAILABLE, returned.getItem().getStatus());
    }

    @Test
    @DisplayName("processReturn: a return inside the due date carries no fine")
    public void testProcessReturn_OnTimeReturnHasNoFine() {
        LocalDateTime now = LocalDateTime.now(IST);
        Loan loan = activeLoan(now.minusDays(2), now.plusDays(5), new BigDecimal("30"), BigDecimal.ZERO);

        when(loanRepository.findById(loan.getLoanId())).thenReturn(Optional.of(loan));
        when(userRepository.findById(any())).thenReturn(Optional.of(new User()));
        when(loanRepository.save(any(Loan.class))).thenAnswer(call -> call.getArgument(0));

        Loan returned = rentalService.processReturn(loan.getLoanId(), UUID.randomUUID(), null);

        assertEquals(0, new BigDecimal("60").compareTo(returned.getRentAmount()), "2 days at 30/day");
        assertEquals(0, BigDecimal.ZERO.compareTo(returned.getFineAmount()));
        // The config service is never consulted, because the fine branch is
        // not entered at all. Strict stubs would fail this test if it were.
    }

    @Test
    @DisplayName("processReturn: a same-day return still bills one full day")
    public void testProcessReturn_SameDayReturnBillsOneDay() {
        LocalDateTime now = LocalDateTime.now(IST);
        // Issued and returned on the same calendar day: zero days elapsed.
        Loan loan = activeLoan(now, now.plusDays(7), new BigDecimal("45"), BigDecimal.ZERO);

        when(loanRepository.findById(loan.getLoanId())).thenReturn(Optional.of(loan));
        when(userRepository.findById(any())).thenReturn(Optional.of(new User()));
        when(loanRepository.save(any(Loan.class))).thenAnswer(call -> call.getArgument(0));

        Loan returned = rentalService.processReturn(loan.getLoanId(), UUID.randomUUID(), null);

        // The service floors days kept at 1, so a same-day rental is not free.
        assertEquals(0, new BigDecimal("45").compareTo(returned.getRentAmount()));
    }

    @Test
    @DisplayName("processReturn: a valid coupon discounts the rent but not the fine")
    public void testProcessReturn_AppliesCouponDiscount() {
        LocalDateTime now = LocalDateTime.now(IST);
        Loan loan = activeLoan(now.minusDays(2), now.plusDays(5), new BigDecimal("100"), BigDecimal.ZERO);

        Coupon coupon = new Coupon();
        coupon.setCode("DIWALI25");
        coupon.setDiscountPercentage(25);

        when(loanRepository.findById(loan.getLoanId())).thenReturn(Optional.of(loan));
        when(userRepository.findById(any())).thenReturn(Optional.of(new User()));
        when(couponRepository.findByCodeIgnoreCaseAndActiveTrue("DIWALI25")).thenReturn(Optional.of(coupon));
        when(loanRepository.save(any(Loan.class))).thenAnswer(call -> call.getArgument(0));

        Loan returned = rentalService.processReturn(loan.getLoanId(), UUID.randomUUID(), "DIWALI25");

        // 2 days at 100/day = 200, less 25% = 150.
        assertEquals(0, new BigDecimal("150").compareTo(returned.getRentAmount()));
    }

    @Test
    @DisplayName("processReturn: a discount above 100% is clamped, never turning into a credit")
    public void testProcessReturn_ClampsOversizedDiscount() {
        LocalDateTime now = LocalDateTime.now(IST);
        Loan loan = activeLoan(now.minusDays(2), now.plusDays(5), new BigDecimal("100"), BigDecimal.ZERO);

        // A bad data entry: 150% off would otherwise compute a negative rent
        // and pay the customer to borrow the film.
        Coupon coupon = new Coupon();
        coupon.setCode("OOPS150");
        coupon.setDiscountPercentage(150);

        when(loanRepository.findById(loan.getLoanId())).thenReturn(Optional.of(loan));
        when(userRepository.findById(any())).thenReturn(Optional.of(new User()));
        when(couponRepository.findByCodeIgnoreCaseAndActiveTrue("OOPS150")).thenReturn(Optional.of(coupon));
        when(loanRepository.save(any(Loan.class))).thenAnswer(call -> call.getArgument(0));

        Loan returned = rentalService.processReturn(loan.getLoanId(), UUID.randomUUID(), "OOPS150");

        assertEquals(0, BigDecimal.ZERO.compareTo(returned.getRentAmount()),
                "clamped to 100% off, so the rent floors at zero");
    }

    @Test
    @DisplayName("processReturn: an unknown coupon code is rejected outright")
    public void testProcessReturn_RejectsUnknownCoupon() {
        LocalDateTime now = LocalDateTime.now(IST);
        Loan loan = activeLoan(now.minusDays(1), now.plusDays(6), new BigDecimal("10"), BigDecimal.ZERO);

        when(loanRepository.findById(loan.getLoanId())).thenReturn(Optional.of(loan));
        when(userRepository.findById(any())).thenReturn(Optional.of(new User()));
        when(couponRepository.findByCodeIgnoreCaseAndActiveTrue("badcode")).thenReturn(Optional.empty());

        Exception exception = assertThrows(RuntimeException.class, () ->
                rentalService.processReturn(loan.getLoanId(), UUID.randomUUID(), "badcode"));

        assertEquals("Invalid or Expired Coupon Code: BADCODE", exception.getMessage());
    }

    @Test
    @DisplayName("processReturn: an already-returned loan cannot be returned twice")
    public void testProcessReturn_RejectsDoubleReturn() {
        LocalDateTime now = LocalDateTime.now(IST);
        Loan loan = activeLoan(now.minusDays(5), now.minusDays(1), new BigDecimal("10"), BigDecimal.ZERO);
        loan.setStatus(LoanStatus.RETURNED);

        when(loanRepository.findById(loan.getLoanId())).thenReturn(Optional.of(loan));
        when(userRepository.findById(any())).thenReturn(Optional.of(new User()));

        Exception exception = assertThrows(RuntimeException.class, () ->
                rentalService.processReturn(loan.getLoanId(), UUID.randomUUID(), null));

        // Without this guard, returning twice would bill the member twice.
        assertEquals("This item is already returned or cancelled.", exception.getMessage());
    }

    // ==========================================
    // HELPERS
    // ==========================================

    /** An ACTIVE loan whose item is on loan, ready to be handed to processReturn. */
    private Loan activeLoan(LocalDateTime issueDate,
                            LocalDateTime dueDate,
                            BigDecimal dailyRate,
                            BigDecimal memberDues) {
        InventoryItem item = new InventoryItem();
        item.setItemId(UUID.randomUUID());
        item.setDailyRate(dailyRate);
        item.setStatus(ItemStatus.ON_LOAN);

        Member member = new Member();
        member.setMemberId(UUID.randomUUID());
        member.setCurrentDues(memberDues);

        Loan loan = new Loan();
        loan.setLoanId(UUID.randomUUID());
        loan.setItem(item);
        loan.setMember(member);
        loan.setIssueDate(issueDate);
        loan.setDueDate(dueDate);
        loan.setStatus(LoanStatus.ACTIVE);
        return loan;
    }
}
