package org.enthusia.playtime.api;

public enum PlaytimeRange {
    TODAY,
    LAST_7D,
    LAST_30D,
    ALL;

    public static PlaytimeRange fromConfigKey(String key) {
        String k = key.toLowerCase();
        return switch (k) {
            case "today" -> TODAY;
            case "7d" -> LAST_7D;
            case "30d" -> LAST_30D;
            default -> ALL;
        };
    }
}
