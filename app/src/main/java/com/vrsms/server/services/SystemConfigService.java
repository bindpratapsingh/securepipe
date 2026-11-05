package com.vrsms.server.services;

import com.vrsms.server.models.SystemConfig;
import com.vrsms.server.repositories.SystemConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SystemConfigService {

    @Autowired
    private SystemConfigRepository configRepository;

    // This method grabs the single configuration row (ID 1) from Supabase
    public SystemConfig getConfig() {
        return configRepository.findById(1)
                .orElseThrow(() -> new RuntimeException("System Configuration not found! Please add row ID 1 to the database."));
    }
}