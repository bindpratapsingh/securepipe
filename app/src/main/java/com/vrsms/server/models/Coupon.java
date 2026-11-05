package com.vrsms.server.models;

import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;

@Entity
@Table(name = "coupons")
public class Coupon {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID couponId;

    @Column(unique = true, nullable = false)
    private String code; // e.g., "DIWALI50"

    @Min(value = 0, message = "Discount cannot be less than 0%")
    @Max(value = 100, message = "Discount cannot exceed 100%")
    private int discountPercentage; // e.g., 50

    private boolean active = true;

    // Getters and Setters
    public UUID getCouponId() { return couponId; }
    public void setCouponId(UUID couponId) { this.couponId = couponId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public int getDiscountPercentage() { return discountPercentage; }
    public void setDiscountPercentage(int discountPercentage) { this.discountPercentage = discountPercentage; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}