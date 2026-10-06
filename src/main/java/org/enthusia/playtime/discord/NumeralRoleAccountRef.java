package org.enthusia.playtime.discord;

import java.util.Objects;

/** Provider-neutral opaque identity used only by the legacy numeral-role compatibility boundary. */
public record NumeralRoleAccountRef(String value) {
    public static final int MAX_LENGTH = 128;

    public NumeralRoleAccountRef {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.length() > MAX_LENGTH
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Numeral role account reference must be 1-128 printable characters");
        }
    }
}
