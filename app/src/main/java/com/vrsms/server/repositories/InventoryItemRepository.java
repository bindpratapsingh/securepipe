package com.vrsms.server.repositories;

import com.vrsms.server.models.InventoryItem;
import com.vrsms.server.models.ItemStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {
    // Custom search method to find movies by name
    List<InventoryItem> findByTitleContainingIgnoreCase(String title);
}