package com.iimsoft.schduler.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Work calendar that determines whether a given absolute hour is a working hour.
 * Configuration is loaded from JVM system property "work.calendar" (JSON format),
 * or uses a default configuration if not specified.
 * 
 * Default configuration:
 * - Day shift: 08:00-20:00 with breaks 12:00-13:00, 18:00-19:00
 * - Night shift: 20:00-08:00 (no breaks)
 */
public class WorkCalendar {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkCalendar.class);
    private static final String SYSTEM_PROPERTY_KEY = "work.calendar";
    private static WorkCalendarConfig config;
    
    static {
        initializeConfig();
    }

    /**
     * Initialize the work calendar configuration.
     * Loads from system property or uses default configuration.
     */
    private static void initializeConfig() {
        String configJson = System.getProperty(SYSTEM_PROPERTY_KEY);
        
        if (configJson != null && !configJson.trim().isEmpty()) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                config = mapper.readValue(configJson, WorkCalendarConfig.class);
                LOGGER.info("Work calendar loaded from system property: {} shifts configured", 
                    config.getShifts().size());
            } catch (Exception e) {
                LOGGER.warn("Failed to parse work calendar from system property, using default", e);
                config = createDefaultConfig();
            }
        } else {
            LOGGER.info("No work calendar specified in system property, using default configuration");
            config = createDefaultConfig();
        }
    }

    /**
     * Create the default work calendar configuration.
     * - Day shift: 08:00-20:00 with breaks at 12:00-13:00 and 18:00-19:00
     * - Night shift: 20:00-08:00 (no breaks)
     */
    private static WorkCalendarConfig createDefaultConfig() {
        WorkCalendarConfig defaultConfig = new WorkCalendarConfig();
        
        // Day shift: 08:00-20:00 with two breaks
        WorkCalendarConfig.Shift dayShift = new WorkCalendarConfig.Shift("DayShift", 8, 20);
        dayShift.getBreaks().add(new WorkCalendarConfig.Break(12, 13)); // Lunch break
        dayShift.getBreaks().add(new WorkCalendarConfig.Break(18, 19)); // Dinner break
        
        // Night shift: 20:00-08:00 (next day), no breaks
        WorkCalendarConfig.Shift nightShift = new WorkCalendarConfig.Shift("NightShift", 20, 8);
        
        defaultConfig.getShifts().add(dayShift);
        defaultConfig.getShifts().add(nightShift);
        
        LOGGER.info("Default work calendar created: 2 shifts (day 08:00-20:00 with breaks, night 20:00-08:00)");
        
        return defaultConfig;
    }

    /**
     * Check if the given absolute hour is a working hour.
     * 
     * @param absoluteHour The absolute hour since epoch (can be any non-negative integer)
     * @return true if it's a working hour, false if it's a break or non-shift hour
     */
    public static boolean isWorkingHour(int absoluteHour) {
        if (absoluteHour < 0) {
            return false;
        }
        
        // Convert absolute hour to hour of day (0-23)
        int hourOfDay = absoluteHour % 24;
        
        // Check if this hour falls within any shift and is not during a break
        for (WorkCalendarConfig.Shift shift : config.getShifts()) {
            if (shift.isWithinShift(hourOfDay)) {
                // Hour is within shift, check if it's not during a break
                if (!shift.isDuringBreak(hourOfDay)) {
                    return true;
                }
            }
        }
        
        return false;
    }

    /**
     * Get the current work calendar configuration.
     * Useful for testing or inspection.
     */
    public static WorkCalendarConfig getConfig() {
        return config;
    }

    /**
     * Set a custom work calendar configuration.
     * Useful for testing.
     */
    public static void setConfig(WorkCalendarConfig customConfig) {
        config = customConfig;
        LOGGER.info("Work calendar configuration updated: {} shifts", config.getShifts().size());
    }

    /**
     * Reset to default configuration.
     * Useful for testing.
     */
    public static void resetToDefault() {
        config = createDefaultConfig();
    }
}
