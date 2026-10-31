package net.clanimg.halloween.deco;

import java.util.UUID;

/**
 * Eine platzierte Deko.
 *
 * @param tile Block mit eigenem Datenspeicher (Kopf, Banner, ...): Die Deko-ID steht im Block selbst und wandert beim
 *             Duplizieren der Welt mit. Andere Blöcke (z.B. Kürbis) werden über Koordinaten + Blocktyp erkannt.
 */
public record Deco(UUID id, String world, int x, int y, int z, String material, boolean tile) {
}
