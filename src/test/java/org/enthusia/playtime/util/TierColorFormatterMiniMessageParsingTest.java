package org.enthusia.playtime.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TierColorFormatterMiniMessageParsingTest {

    @Test
    void generatedNamedHexGradientAndLegacyMarkupParsesToTheOriginalText() {
        Component named = parse("gray", "I");
        Component hex = parse("#12AB34", "II");
        Component gradient = parse("gradient:#FF0000:#00FF00", "III");
        Component miniGradient = parse("<gradient:#ff5f6d:#ffc371>", "IV");
        Component legacy = parse("&a&l", "V");

        assertEquals("I", plain(named));
        assertEquals("II", plain(hex));
        assertEquals("III", plain(gradient));
        assertEquals("IV", plain(miniGradient));
        assertEquals("V", plain(legacy));
        assertFalse(hasClickEvent(named));
        assertFalse(hasClickEvent(hex));
        assertFalse(hasClickEvent(gradient));
        assertFalse(hasClickEvent(miniGradient));
        assertFalse(hasClickEvent(legacy));
    }

    @Test
    void injectionShapedLabelRemainsLiteralAndHasNoClickEvent() {
        String label = "<click:run_command:/op me>I";
        Component component = parse("gray", label);

        assertEquals(label, plain(component));
        assertFalse(hasClickEvent(component));
    }

    private Component parse(String color, String label) {
        return MiniMessage.miniMessage().deserialize(
                TierColorFormatter.applyMiniMessage(color, label));
    }

    private String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private boolean hasClickEvent(Component component) {
        boolean found = component.style().clickEvent() != null;
        for (Component child : component.children()) {
            if (hasClickEvent(child)) {
                found = true;
                break;
            }
        }
        return found;
    }
}
