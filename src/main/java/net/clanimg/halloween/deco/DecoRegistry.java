package net.clanimg.halloween.deco;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Alle Deko des Events und wer was schon gefunden hat (Stand aus der Datenbank, hier zwischengespeichert).
 * Thread-sicher: wird vom Server-Thread gelesen und aus Datenbank-Threads aktualisiert.
 */
public final class DecoRegistry {
    private volatile Map<UUID, Deco> decos = Map.of();
    private volatile Map<Long, List<Deco>> blockDecosByPosition = Map.of();
    private final Map<UUID, Set<UUID>> foundByPlayer = new ConcurrentHashMap<>();

    public void replaceAll(Collection<Deco> all) {
        Map<UUID, Deco> byId = new ConcurrentHashMap<>();
        Map<Long, List<Deco>> byPosition = new ConcurrentHashMap<>();
        for (Deco deco : all) {
            byId.put(deco.id(), deco);
            if (!deco.tile()) {
                byPosition.computeIfAbsent(position(deco.x(), deco.y(), deco.z()), key -> new ArrayList<>()).add(deco);
            }
        }
        decos = byId;
        blockDecosByPosition = byPosition;
    }

    public void add(Deco deco) {
        Map<UUID, Deco> byId = new ConcurrentHashMap<>(decos);
        byId.put(deco.id(), deco);
        replaceAll(byId.values());
    }

    public void remove(UUID decoId) {
        Map<UUID, Deco> byId = new ConcurrentHashMap<>(decos);
        byId.remove(decoId);
        replaceAll(byId.values());
        foundByPlayer.values().forEach(found -> found.remove(decoId));
    }

    public int total() {
        return decos.size();
    }

    /** Deko mit dieser ID (aus dem Block gelesen), wenn es sie im Event (noch) gibt. */
    public Optional<Deco> byId(UUID decoId) {
        return Optional.ofNullable(decos.get(decoId));
    }

    /** Deko ohne eigenen Datenspeicher an dieser Stelle; gleiche Welt bevorzugt, Blocktyp muss passen. */
    public Optional<Deco> blockDecoAt(String world, int x, int y, int z, String material) {
        List<Deco> candidates = blockDecosByPosition.getOrDefault(position(x, y, z), List.of());
        Deco fallback = null;
        for (Deco deco : candidates) {
            if (!deco.material().equalsIgnoreCase(material)) {
                continue;
            }
            if (deco.world().equalsIgnoreCase(world)) {
                return Optional.of(deco);
            }
            fallback = fallback == null ? deco : fallback;
        }
        // Andere Welt mit gleichen Koordinaten und Blocktyp: eine Kopie der Welt (z.B. in die Lobby dupliziert).
        return Optional.ofNullable(fallback);
    }

    public void setFound(UUID playerId, Set<UUID> found) {
        Set<UUID> set = ConcurrentHashMap.newKeySet();
        set.addAll(found);
        foundByPlayer.put(playerId, set);
    }

    public boolean hasFound(UUID playerId, UUID decoId) {
        Set<UUID> found = foundByPlayer.get(playerId);
        return found != null && found.contains(decoId);
    }

    /** @return true, wenn die Deko für den Spieler neu ist (dann jetzt als gefunden vorgemerkt) */
    public boolean markFound(UUID playerId, UUID decoId) {
        return foundByPlayer.computeIfAbsent(playerId, id -> ConcurrentHashMap.newKeySet()).add(decoId);
    }

    public void unmarkFound(UUID playerId, UUID decoId) {
        Set<UUID> found = foundByPlayer.get(playerId);
        if (found != null) {
            found.remove(decoId);
        }
    }

    /** Gefundene Deko, die es im Event noch gibt. */
    public int foundCount(UUID playerId) {
        Set<UUID> found = foundByPlayer.get(playerId);
        if (found == null) {
            return 0;
        }
        Map<UUID, Deco> current = decos;
        return (int) found.stream().filter(current::containsKey).count();
    }

    public void forget(UUID playerId) {
        foundByPlayer.remove(playerId);
    }

    private static long position(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }
}
