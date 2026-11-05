package com.vrsms.server.repositories;

import com.vrsms.server.models.InventoryItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface InventoryRepository extends JpaRepository<InventoryItem, UUID> {
    // Spring Boot automatically writes the findAll() and save() SQL for us!
}