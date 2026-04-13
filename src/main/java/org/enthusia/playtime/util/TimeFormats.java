package org.enthusia.playtime.util;

public final class TimeFormats {

    private TimeFormats() {}

    public static String formatMinutes(long minutes) {
        long hours = minutes / 60;
        long mins = minutes % 60;
        if (hours <= 0) {
            return mins + "m";
        }
        return hours + "h " + mins + "m";
    }

    public static String formatDurationMillis(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        long remHours = hours % 24;
        long remMinutes = minutes % 60;

        if (days > 0) {
            return days + "d " + remHours + "h " + remMinutes + "m";
        }
        if (hours > 0) {
            return hours + "h " + remMinutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m";
        }
        return seconds + "s";
    }
}
