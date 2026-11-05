package com.vrsms.server.models;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "wishlists")
public class WishlistItem {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID wishlistId;

    @ManyToOne
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne
    @JoinColumn(name = "item_id", nullable = false)
    private InventoryItem item;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime addedAt;

    public UUID getWishlistId() { return wishlistId; }
    public void setWishlistId(UUID wishlistId) { this.wishlistId = wishlistId; }
    public Member getMember() { return member; }
    public void setMember(Member member) { this.member = member; }
    public InventoryItem getItem() { return item; }
    public void setItem(InventoryItem item) { this.item = item; }
    public LocalDateTime getAddedAt() { return addedAt; }
    public void setAddedAt(LocalDateTime addedAt) { this.addedAt = addedAt; }
}