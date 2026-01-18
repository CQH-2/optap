package com.iimsoft.schduler.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Singleton work calendar that determines which hours are working hours.
 * 
 * Configuration:
 * - Reads from JVM system property "work.calendar" (JSON format)
 * - Falls back to default configuration if not set or parsing fails
 * 
 * Default configuration:
 * - Day shift: 08:00-20:00 with breaks 12:00-13:00 and 18:00-19:00
 * - Night shift: 20:00-08:00 (crosses midnight)
 * 
 * The configuration is compiled into a BitSet for hours 0-23 representing
 * working hours in a typical day pattern.
 */
public class WorkCalendar {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkCalendar.class);
    private static final WorkCalendar INSTANCE = new WorkCalendar();
    
    private final BitSet workingHours; // bits 0-23 for hours in a day
    
    private WorkCalendar() {
        this.workingHours = new BitSet(24);
        loadConfiguration();
    }
    
    public static WorkCalendar getInstance() {
        return INSTANCE;
    }
    
    private void loadConfiguration() {
        String configJson = System.getProperty("work.calendar");
        WorkCalendarConfig config = null;
        
        if (configJson != null && !configJson.isEmpty()) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                config = mapper.readValue(configJson, WorkCalendarConfig.class);
                LOGGER.info("Work calendar loaded from system property 'work.calendar'");
            } catch (Exception e) {
                LOGGER.warn("Failed to parse work.calendar system property, using default configuration", e);
            }
        }
        
        if (config == null) {
            config = getDefaultConfiguration();
            LOGGER.info("Using default work calendar configuration: Day shift 08-20 (breaks: 12-13, 18-19), Night shift 20-08");
        }
        
        compileConfiguration(config);
    }
    
    private WorkCalendarConfig getDefaultConfiguration() {
        WorkCalendarConfig config = new WorkCalendarConfig();
        List<WorkCalendarConfig.Shift> shifts = new ArrayList<>();
        
        // Day shift: 08:00-20:00 with breaks 12:00-13:00 and 18:00-19:00
        List<WorkCalendarConfig.Break> dayBreaks = new ArrayList<>();
        dayBreaks.add(new WorkCalendarConfig.Break(12, 13));
        dayBreaks.add(new WorkCalendarConfig.Break(18, 19));
        shifts.add(new WorkCalendarConfig.Shift(8, 20, dayBreaks));
        
        // Night shift: 20:00-08:00 (crosses midnight)
        shifts.add(new WorkCalendarConfig.Shift(20, 8, new ArrayList<>()));
        
        config.setShifts(shifts);
        return config;
    }
    
    private void compileConfiguration(WorkCalendarConfig config) {
        // Clear all hours first
        workingHours.clear();
        
        if (config.getShifts() == null || config.getShifts().isEmpty()) {
            LOGGER.warn("No shifts configured, all hours will be non-working");
            return;
        }
        
        // Process each shift
        for (WorkCalendarConfig.Shift shift : config.getShifts()) {
            int start = shift.getStartHour();
            int end = shift.getEndHour();
            
            // Set working hours for this shift
            if (end <= start) {
                // Cross-day shift: e.g., 20-08 means 20,21,22,23,0,1,2,3,4,5,6,7
                for (int h = start; h < 24; h++) {
                    workingHours.set(h);
                }
                for (int h = 0; h < end; h++) {
                    workingHours.set(h);
                }
            } else {
                // Normal shift: e.g., 08-20 means 8,9,10,...,19
                for (int h = start; h < end; h++) {
                    workingHours.set(h);
                }
            }
            
            // Remove break hours
            if (shift.getBreaks() != null) {
                for (WorkCalendarConfig.Break brk : shift.getBreaks()) {
                    int breakStart = brk.getStartHour();
                    int breakEnd = brk.getEndHour();
                    
                    if (breakEnd <= breakStart) {
                        // Cross-day break
                        for (int h = breakStart; h < 24; h++) {
                            workingHours.clear(h);
                        }
                        for (int h = 0; h < breakEnd; h++) {
                            workingHours.clear(h);
                        }
                    } else {
                        // Normal break
                        for (int h = breakStart; h < breakEnd; h++) {
                            workingHours.clear(h);
                        }
                    }
                }
            }
        }
        
        // Log the working hours pattern
        if (LOGGER.isDebugEnabled()) {
            StringBuilder pattern = new StringBuilder("Working hours pattern (24h): ");
            for (int h = 0; h < 24; h++) {
                pattern.append(workingHours.get(h) ? "1" : "0");
            }
            LOGGER.debug(pattern.toString());
        }
    }
    
    /**
     * Check if a given absolute hour is a working hour.
     * 
     * @param absoluteHour absolute hour from project start (can be any positive integer)
     * @return true if this hour is a working hour
     */
    public boolean isWorkingHour(int absoluteHour) {
        // Map absolute hour to hour-of-day (0-23) using modulo
        int hourOfDay = absoluteHour % 24;
        return workingHours.get(hourOfDay);
    }
    
    /**
     * Get the working hours pattern for a day.
     * Useful for debugging and testing.
     */
    public BitSet getWorkingHoursPattern() {
        return (BitSet) workingHours.clone();
    }
}
