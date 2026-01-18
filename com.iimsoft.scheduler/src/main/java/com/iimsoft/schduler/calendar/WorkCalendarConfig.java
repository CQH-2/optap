package com.iimsoft.schduler.calendar;

import java.util.List;

/**
 * Configuration for work shifts and breaks.
 * Supports multiple shifts per day and cross-day shifts (e.g., night shift 20:00-08:00).
 * 
 * Cross-day shift detection: when endHour <= startHour, the shift spans midnight.
 */
public class WorkCalendarConfig {
    
    private List<Shift> shifts;
    
    public WorkCalendarConfig() {
    }
    
    public List<Shift> getShifts() {
        return shifts;
    }
    
    public void setShifts(List<Shift> shifts) {
        this.shifts = shifts;
    }
    
    public static class Shift {
        private int startHour;  // 0-23
        private int endHour;    // 0-23, when <= startHour means crosses midnight
        private List<Break> breaks;
        
        public Shift() {
        }
        
        public Shift(int startHour, int endHour, List<Break> breaks) {
            this.startHour = startHour;
            this.endHour = endHour;
            this.breaks = breaks;
        }
        
        public int getStartHour() {
            return startHour;
        }
        
        public void setStartHour(int startHour) {
            this.startHour = startHour;
        }
        
        public int getEndHour() {
            return endHour;
        }
        
        public void setEndHour(int endHour) {
            this.endHour = endHour;
        }
        
        public List<Break> getBreaks() {
            return breaks;
        }
        
        public void setBreaks(List<Break> breaks) {
            this.breaks = breaks;
        }
    }
    
    public static class Break {
        private int startHour;  // 0-23
        private int endHour;    // 0-23, when <= startHour means crosses midnight
        
        public Break() {
        }
        
        public Break(int startHour, int endHour) {
            this.startHour = startHour;
            this.endHour = endHour;
        }
        
        public int getStartHour() {
            return startHour;
        }
        
        public void setStartHour(int startHour) {
            this.startHour = startHour;
        }
        
        public int getEndHour() {
            return endHour;
        }
        
        public void setEndHour(int endHour) {
            this.endHour = endHour;
        }
    }
}
