package com.vrsms.server.repositories;

import com.vrsms.server.models.WishlistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WishlistRepository extends JpaRepository<WishlistItem, UUID> {
    // Fetches the wishlist and sorts it so newest additions are at the top!
    List<WishlistItem> findByMember_MemberIdOrderByAddedAtDesc(UUID memberId);

    // Checks if a movie is already on the list
    Optional<WishlistItem> findByMember_MemberIdAndItem_ItemId(UUID memberId, UUID itemId);
}