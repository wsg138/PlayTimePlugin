package org.enthusia.playtime.util;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Converts configured numeral tier colors into legacy or MiniMessage text. */
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

    /** Produces the existing legacy-colored output used by chat and legacy placeholders. */
    public static String apply(String specification, String text) {
        ParsedColor color = parse(specification);
        if (color.gradient().isEmpty()) {
            return color.legacyPrefix() + text;
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

    /** Produces MiniMessage markup without deserializing it into a component. */
    public static String applyMiniMessage(String specification, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        ParsedColor color = parse(specification);
        return color.miniMessageOpen()
                + escapeMiniMessageText(text)
                + color.miniMessageClose();
    }

    public static String prefix(String specification) {
        return parse(specification).legacyPrefix();
    }

    public static String miniMessagePrefix(String specification) {
        return parse(specification).miniMessageOpen();
    }

    public static String replaceTierLabelTokens(String template, NumeralTierCatalog.Tier tier) {
        String label = tier == null ? "None" : tier.label();
        String prefix = tier == null ? ChatColor.DARK_GRAY.toString() : prefix(tier.color());
        String coloredLabel = tier == null ? prefix + label : apply(tier.color(), label);
        return template.replace("%tier_color%%tier_label%", coloredLabel)
                .replace("%tier_label%", label)
                .replace("%tier_color%", prefix);
    }

    private static String escapeMiniMessageText(String text) {
        return text.replace("\\", "\\\\").replace("<", "\\<");
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
        if (!LEGACY.matcher(value).matches()) {
            return null;
        }
        LegacyMiniMessage legacyMiniMessage = resolveLegacyMiniMessage(value);
        return new ParsedColor(
                ChatColor.translateAlternateColorCodes('&', value),
                List.of(),
                legacyMiniMessage.open(),
                legacyMiniMessage.close()
        );
    }

    private static LegacyMiniMessage resolveLegacyMiniMessage(String value) {
        String color = null;
        Set<String> decorations = new LinkedHashSet<>();
        for (int index = 0; index < value.length(); index += 2) {
            char code = Character.toLowerCase(value.charAt(index + 1));
            String namedColor = legacyColorName(code);
            if (namedColor != null) {
                color = namedColor;
                decorations.clear();
                continue;
            }
            if (code == 'r') {
                color = null;
                decorations.clear();
                continue;
            }
            String decoration = legacyDecorationName(code);
            if (decoration != null) {
                decorations.add(decoration);
            }
        }

        StringBuilder open = new StringBuilder();
        List<String> opened = new ArrayList<>();
        if (color != null) {
            open.append('<').append(color).append('>');
            opened.add(color);
        }
        for (String decoration : decorations) {
            open.append('<').append(decoration).append('>');
            opened.add(decoration);
        }

        StringBuilder close = new StringBuilder();
        for (int index = opened.size() - 1; index >= 0; index--) {
            close.append("</").append(opened.get(index)).append('>');
        }
        return new LegacyMiniMessage(open.toString(), close.toString());
    }

    private static String legacyColorName(char code) {
        return switch (code) {
            case '0' -> "black";
            case '1' -> "dark_blue";
            case '2' -> "dark_green";
            case '3' -> "dark_aqua";
            case '4' -> "dark_red";
            case '5' -> "dark_purple";
            case '6' -> "gold";
            case '7' -> "gray";
            case '8' -> "dark_gray";
            case '9' -> "blue";
            case 'a' -> "green";
            case 'b' -> "aqua";
            case 'c' -> "red";
            case 'd' -> "light_purple";
            case 'e' -> "yellow";
            case 'f' -> "white";
            default -> null;
        };
    }

    private static String legacyDecorationName(char code) {
        return switch (code) {
            case 'k' -> "obfuscated";
            case 'l' -> "bold";
            case 'm' -> "strikethrough";
            case 'n' -> "underlined";
            case 'o' -> "italic";
            default -> null;
        };
    }

    private static ParsedColor parseNamed(String value) {
        String normalizedName = value.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            ChatColor named = ChatColor.valueOf(normalizedName);
            if (named.isColor()) {
                String miniMessageName = normalizedName.toLowerCase(Locale.ROOT);
                return new ParsedColor(
                        named.toString(),
                        List.of(),
                        '<' + miniMessageName + '>',
                        "</" + miniMessageName + '>'
                );
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
            int rgb = parseHex(normalizedHex);
            String canonical = canonicalHex(rgb);
            return new ParsedColor(
                    hexColor(rgb),
                    List.of(),
                    '<' + canonical + '>',
                    "</" + canonical + '>'
            );
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

        StringBuilder miniMessageOpen = new StringBuilder("<gradient");
        for (int stop : stops) {
            miniMessageOpen.append(':').append(canonicalHex(stop));
        }
        miniMessageOpen.append('>');
        return new ParsedColor(
                hexColor(stops.getFirst()),
                List.copyOf(stops),
                miniMessageOpen.toString(),
                "</gradient>"
        );
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

    private static String canonicalHex(int rgb) {
        return String.format(Locale.ROOT, "#%06x", rgb);
    }

    private static String hexColor(int rgb) {
        return net.md_5.bungee.api.ChatColor.of(String.format(Locale.ROOT, "#%06X", rgb)).toString();
    }

    private record ParsedColor(
            String legacyPrefix,
            List<Integer> gradient,
            String miniMessageOpen,
            String miniMessageClose
    ) {
    }

    private record LegacyMiniMessage(String open, String close) {
    }
}
