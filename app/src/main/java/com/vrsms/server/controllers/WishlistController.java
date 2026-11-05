package com.vrsms.server.controllers;

import com.vrsms.server.models.InventoryItem;
import com.vrsms.server.models.Member;
import com.vrsms.server.models.WishlistItem;
import com.vrsms.server.repositories.InventoryItemRepository;
import com.vrsms.server.repositories.MemberRepository;
import com.vrsms.server.repositories.WishlistRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/wishlist")
@CrossOrigin(origins = "*")
public class WishlistController {

    @Autowired private WishlistRepository wishlistRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private InventoryItemRepository inventoryRepository;

    // ==========================================
    // GET ALL WISHLIST ITEMS FOR A MEMBER
    // ==========================================
    @GetMapping("/{userId}")
    public ResponseEntity<?> getMemberWishlist(@PathVariable UUID userId) {
        try {
            // FIX: Lookup the member using the User ID sent by React
            Member member = memberRepository.findByUser_UserId(userId)
                    .orElseThrow(() -> new RuntimeException("Member not found"));

            List<WishlistItem> rawList = wishlistRepository.findByMember_MemberIdOrderByAddedAtDesc(member.getMemberId());

            List<InventoryItem> items = rawList.stream().map(WishlistItem::getItem).collect(Collectors.toList());
            return ResponseEntity.ok(items);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ==========================================
    // THE TOGGLE ENDPOINT
    // ==========================================
    @PostMapping("/toggle")
    public ResponseEntity<?> toggleWishlist(@RequestBody ToggleRequest request) {
        try {
            // FIX: The frontend sends the User's ID. Translate it to the Member profile!
            Member member = memberRepository.findByUser_UserId(request.getMemberId())
                    .orElseThrow(() -> new RuntimeException("Member profile not found."));

            java.util.Optional<WishlistItem> existing = wishlistRepository
                    .findByMember_MemberIdAndItem_ItemId(member.getMemberId(), request.getItemId());

            if (existing.isPresent()) {
                // If it's already there, remove it
                wishlistRepository.delete(existing.get());
                return ResponseEntity.ok("REMOVED"); // Matches React state check
            } else {
                // If it's not there, add it
                InventoryItem item = inventoryRepository.findById(request.getItemId())
                        .orElseThrow(() -> new RuntimeException("Item not found"));

                WishlistItem newItem = new WishlistItem();
                newItem.setMember(member);
                newItem.setItem(item);
                wishlistRepository.save(newItem);

                return ResponseEntity.ok("ADDED"); // Matches React state check
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Failed to toggle wishlist: " + e.getMessage());
        }
    }

    // DTO for the React payload
    public static class ToggleRequest {
        private java.util.UUID memberId; // React sends the user.userId inside this field
        private java.util.UUID itemId;

        public java.util.UUID getMemberId() { return memberId; }
        public void setMemberId(java.util.UUID memberId) { this.memberId = memberId; }
        public java.util.UUID getItemId() { return itemId; }
        public void setItemId(java.util.UUID itemId) { this.itemId = itemId; }
    }
}