package com.iimsoft.schduler.calendar;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for work calendar, defining shifts and breaks.
 * Supports multiple shifts including night shifts that span across midnight.
 */
public class WorkCalendarConfig {

    @JsonProperty("shifts")
    private List<Shift> shifts;

    public WorkCalendarConfig() {
        this.shifts = new ArrayList<>();
    }

    public List<Shift> getShifts() {
        return shifts;
    }

    public void setShifts(List<Shift> shifts) {
        this.shifts = shifts;
    }

    /**
     * Represents a work shift with start/end hours and optional breaks.
     * Supports cross-day shifts (e.g., night shift 20:00-08:00).
     */
    public static class Shift {
        @JsonProperty("name")
        private String name;

        @JsonProperty("startHour")
        private int startHour; // 0-23

        @JsonProperty("endHour")
        private int endHour; // 0-23, can be less than startHour for night shifts

        @JsonProperty("breaks")
        private List<Break> breaks;

        public Shift() {
            this.breaks = new ArrayList<>();
        }

        public Shift(String name, int startHour, int endHour) {
            this.name = name;
            this.startHour = startHour;
            this.endHour = endHour;
            this.breaks = new ArrayList<>();
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
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

        /**
         * Check if the given hour (within a 24-hour day) falls within this shift.
         * For night shifts (endHour < startHour), it wraps around midnight.
         */
        public boolean isWithinShift(int hourOfDay) {
            hourOfDay = hourOfDay % 24;
            if (endHour > startHour) {
                // Normal shift within same day
                return hourOfDay >= startHour && hourOfDay < endHour;
            } else {
                // Night shift spanning midnight
                return hourOfDay >= startHour || hourOfDay < endHour;
            }
        }

        /**
         * Check if the given hour is during a break period.
         */
        public boolean isDuringBreak(int hourOfDay) {
            hourOfDay = hourOfDay % 24;
            if (breaks == null) {
                return false;
            }
            for (Break b : breaks) {
                if (b.isDuringBreak(hourOfDay)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Represents a break period within a shift.
     */
    public static class Break {
        @JsonProperty("startHour")
        private int startHour; // 0-23

        @JsonProperty("endHour")
        private int endHour; // 0-23

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

        /**
         * Check if the given hour falls within this break.
         * Supports cross-day breaks (though typically not needed).
         */
        public boolean isDuringBreak(int hourOfDay) {
            hourOfDay = hourOfDay % 24;
            if (endHour > startHour) {
                return hourOfDay >= startHour && hourOfDay < endHour;
            } else {
                // Cross-day break (rare)
                return hourOfDay >= startHour || hourOfDay < endHour;
            }
        }
    }
}
