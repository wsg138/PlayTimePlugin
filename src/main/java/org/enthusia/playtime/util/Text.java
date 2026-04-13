package org.enthusia.playtime.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class Text {

    private Text() {}

    public static Component prefix() {
        return Component.text("[Playtime] ", NamedTextColor.GOLD);
    }

    public static Component error(String msg) {
        return prefix().append(Component.text(msg, NamedTextColor.RED));
    }

    public static Component info(String msg) {
        return prefix().append(Component.text(msg, NamedTextColor.YELLOW));
    }
}
