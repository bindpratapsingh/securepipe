package com.vrsms.server.models;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "system_config")
public class SystemConfig {
    @Id
    private Integer configId = 1;
    private Integer maxRentalDays;
    private BigDecimal lateFeePerDay;

    public Integer getConfigId() { return configId; }
    public void setConfigId(Integer configId) { this.configId = configId; }
    public Integer getMaxRentalDays() { return maxRentalDays; }
    public void setMaxRentalDays(Integer maxRentalDays) { this.maxRentalDays = maxRentalDays; }
    public BigDecimal getLateFeePerDay() { return lateFeePerDay; }
    public void setLateFeePerDay(BigDecimal lateFeePerDay) { this.lateFeePerDay = lateFeePerDay; }
}