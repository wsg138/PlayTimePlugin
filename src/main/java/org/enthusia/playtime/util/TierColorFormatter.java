package org.enthusia.playtime.util;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Converts configured numeral tier colors into legacy text understood by chat and placeholders. */
public final class TierColorFormatter {
    private static final Pattern LEGACY = Pattern.compile("(?i)(?:&[0-9A-FK-OR])+");
    private static final Pattern HEX = Pattern.compile("(?i)#?[0-9A-F]{6}");

    private TierColorFormatter() {
    }

    public static boolean isValid(String specification) {
        try {
            parse(specification);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static String apply(String specification, String text) {
        ParsedColor color = parse(specification);
        if (color.gradient().isEmpty()) {
            return color.prefix() + text;
        }
        int[] codePoints = text.codePoints().toArray();
        if (codePoints.length == 0) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < codePoints.length; index++) {
            double position = codePoints.length == 1 ? 0.0D : (double) index / (codePoints.length - 1);
            result.append(hexColor(interpolate(color.gradient(), position)));
            result.appendCodePoint(codePoints[index]);
        }
        return result.toString();
    }

    public static String prefix(String specification) {
        return parse(specification).prefix();
    }

    public static String replaceTierLabelTokens(String template, NumeralTierCatalog.Tier tier) {
        String label = tier == null ? "None" : tier.label();
        String prefix = tier == null ? ChatColor.DARK_GRAY.toString() : prefix(tier.color());
        String coloredLabel = tier == null ? prefix + label : apply(tier.color(), label);
        return template.replace("%tier_color%%tier_label%", coloredLabel)
                .replace("%tier_label%", label)
                .replace("%tier_color%", prefix);
    }

    private static ParsedColor parse(String specification) {
        if (specification == null || specification.isBlank()) {
            throw new IllegalArgumentException("Color specification is blank");
        }
        String value = specification.trim();
        ParsedColor parsed = parseLegacy(value);
        if (parsed == null) parsed = parseNamed(value);
        if (parsed == null) parsed = parseSingleHex(value);
        if (parsed == null) parsed = parseGradient(value);
        if (parsed != null) return parsed;
        throw new IllegalArgumentException("Unsupported color specification");
    }

    private static ParsedColor parseLegacy(String value) {
        if (LEGACY.matcher(value).matches()) {
            return new ParsedColor(ChatColor.translateAlternateColorCodes('&', value), List.of());
        }
        return null;
    }

    private static ParsedColor parseNamed(String value) {
        String normalizedName = value.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            ChatColor named = ChatColor.valueOf(normalizedName);
            if (named.isColor()) {
                return new ParsedColor(named.toString(), List.of());
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }

    private static ParsedColor parseSingleHex(String value) {
        String candidate = value.startsWith("&#") ? value.substring(1) : value;
        if (HEX.matcher(candidate).matches()) {
            String normalizedHex = candidate.startsWith("#") ? candidate : "#" + candidate;
            return new ParsedColor(hexColor(parseHex(normalizedHex)), List.of());
        }
        return null;
    }

    private static ParsedColor parseGradient(String value) {
        String gradient = value;
        if (gradient.startsWith("<") && gradient.endsWith(">")) {
            gradient = gradient.substring(1, gradient.length() - 1);
        }
        if (!gradient.toLowerCase(Locale.ROOT).startsWith("gradient:")) return null;
        String[] parts = gradient.substring("gradient:".length()).split(":");
        List<Integer> stops = new ArrayList<>();
        for (String part : parts) {
            if (!HEX.matcher(part).matches()) {
                throw new IllegalArgumentException("Invalid gradient stop");
            }
            stops.add(parseHex(part.startsWith("#") ? part : "#" + part));
        }
        if (stops.size() < 2) throw new IllegalArgumentException("Gradient requires at least two stops");
        return new ParsedColor(hexColor(stops.getFirst()), List.copyOf(stops));
    }

    private static int interpolate(List<Integer> stops, double position) {
        double scaled = position * (stops.size() - 1);
        int segment = Math.min((int) scaled, stops.size() - 2);
        double local = scaled - segment;
        int start = stops.get(segment);
        int end = stops.get(segment + 1);
        int red = channel(start, 16, end, local);
        int green = channel(start, 8, end, local);
        int blue = channel(start, 0, end, local);
        return red << 16 | green << 8 | blue;
    }

    private static int channel(int start, int shift, int end, double position) {
        int from = start >> shift & 0xFF;
        int to = end >> shift & 0xFF;
        return (int) Math.round(from + (to - from) * position);
    }

    private static int parseHex(String value) {
        return Integer.parseInt(value.substring(1), 16);
    }

    private static String hexColor(int rgb) {
        return net.md_5.bungee.api.ChatColor.of(String.format("#%06X", rgb)).toString();
    }

    private record ParsedColor(String prefix, List<Integer> gradient) {
    }
}
