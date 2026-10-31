package net.clanimg.halloween;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** Texte aus der Config: mit &-Farbcodes als Legacy, sonst MiniMessage. */
public final class Text {
    private Text() {
    }

    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        if (raw.indexOf('&') >= 0 || raw.indexOf('§') >= 0) {
            return LegacyComponentSerializer.legacyAmpersand().deserialize(raw.replace('§', '&'));
        }
        return MiniMessage.miniMessage().deserialize(raw);
    }
}
