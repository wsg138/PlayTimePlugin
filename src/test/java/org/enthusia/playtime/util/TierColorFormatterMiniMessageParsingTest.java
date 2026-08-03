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
        assertParses("gray", "I");
        assertParses("#12AB34", "II");
        assertParses("gradient:#FF0000:#00FF00", "III");
        assertParses("<gradient:#ff5f6d:#ffc371>", "IV");
        assertParses("&a&l", "V");
    }

    @Test
    void injectionShapedLabelRemainsLiteralAndHasNoClickEvent() {
        String label = "<click:run_command:/op me>I";
        Component component = MiniMessage.miniMessage().deserialize(
                TierColorFormatter.applyMiniMessage("gray", label));

        assertEquals(label, PlainTextComponentSerializer.plainText().serialize(component));
        assertFalse(hasClickEvent(component));
    }

    private void assertParses(String color, String label) {
        Component component = MiniMessage.miniMessage().deserialize(
                TierColorFormatter.applyMiniMessage(color, label));
        assertEquals(label, PlainTextComponentSerializer.plainText().serialize(component));
        assertFalse(hasClickEvent(component));
    }

    private boolean hasClickEvent(Component component) {
        if (component.style().clickEvent() != null) {
            return true;
        }
        return component.children().stream().anyMatch(this::hasClickEvent);
    }
}
