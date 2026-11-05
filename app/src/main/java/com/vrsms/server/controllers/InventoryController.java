package com.vrsms.server.controllers;

import com.vrsms.server.models.InventoryItem;
import com.vrsms.server.models.ItemStatus;
import com.vrsms.server.repositories.InventoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.stream.Collectors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/inventory")
// Explicitly allowing your local and live React apps!
@CrossOrigin(origins = {"http://localhost:5173", "https://vrsms-frontend.vercel.app"})
public class InventoryController {

    @Autowired
    private InventoryRepository inventoryRepository;

    @GetMapping("/available")
    public ResponseEntity<List<InventoryItem>> getAvailableInventory() {
        try {
            // 1. Get every item in the database
            List<InventoryItem> allItems = inventoryRepository.findAll();

            // 2. Filter out the ones that are already rented!
            List<InventoryItem> actuallyAvailable = allItems.stream()
                    .filter(item -> item.getStatus() == ItemStatus.AVAILABLE)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(actuallyAvailable);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body(null);
        }
    }

    @PostMapping("/add")
    public ResponseEntity<?> addInventoryItem(@RequestBody InventoryItemRequest request) {
        try {
            // If they didn't specify a quantity, default to 1.
            int qty = (request.getQuantity() != null && request.getQuantity() > 0) ? request.getQuantity() : 1;

            // Loop and create unique physical copies!
            for(int i = 0; i < qty; i++) {
                InventoryItem item = new InventoryItem();
                item.setTitle(request.getTitle());
                item.setCategory(request.getCategory());
                item.setFormat(request.getFormat());
                item.setPurchasePrice(request.getPurchasePrice());
                item.setDailyRate(request.getDailyRate());
                item.setImageUrl(request.getImageUrl());
                item.setPurchasedOn(LocalDate.now());
                item.setStatus(ItemStatus.AVAILABLE);

                inventoryRepository.save(item);
            }

            return ResponseEntity.ok("Successfully added " + qty + " copies of " + request.getTitle() + "!");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Failed to add items: " + e.getMessage());
        }
    }

    // --- THE BRAND NEW EDIT ENDPOINT ---
    @PutMapping("/edit/{id}")
    public ResponseEntity<?> updateItem(@PathVariable String id, @RequestBody InventoryItemRequest request) {
        try {
            UUID itemUuid = UUID.fromString(id);

            InventoryItem existingItem = inventoryRepository.findById(itemUuid)
                    .orElseThrow(() -> new RuntimeException("Item not found."));

            existingItem.setTitle(request.getTitle());
            existingItem.setCategory(request.getCategory());
            existingItem.setFormat(request.getFormat());
            existingItem.setPurchasePrice(request.getPurchasePrice());
            existingItem.setDailyRate(request.getDailyRate());
            existingItem.setImageUrl(request.getImageUrl()); // <-- Added Image URL

            inventoryRepository.save(existingItem);
            return ResponseEntity.ok("Item updated successfully!");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body("Failed to update item: " + e.getMessage());
        }
    }

    // --- DTO for incoming data ---
    public static class InventoryItemRequest {
        private String title;
        private String category;
        private String format;
        private BigDecimal purchasePrice;
        private BigDecimal dailyRate;
        private String imageUrl; // <-- Added Image URL here!
        private Integer quantity;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }

        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }

        public BigDecimal getPurchasePrice() { return purchasePrice; }
        public void setPurchasePrice(BigDecimal purchasePrice) { this.purchasePrice = purchasePrice; }

        public BigDecimal getDailyRate() { return dailyRate; }
        public void setDailyRate(BigDecimal dailyRate) { this.dailyRate = dailyRate; }

        // Getters and Setters for Image URL!
        public String getImageUrl() { return imageUrl; }
        public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }

    }
}