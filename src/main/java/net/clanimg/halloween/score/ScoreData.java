package net.clanimg.halloween.score;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Punkte pro Welt: Jede Welt zählt nur die Deko, die in ihr steht (auch in Kopien), dazu die Korrektur per
 * /halloween set|add|remove für diese Welt. Deko in gelöschten oder anderen Welten zählen hier nicht mit.
 * Unveränderlich - Änderungen liefern einen neuen Stand.
 */
public final class ScoreData {
    /** Korrektur der Punkte eines Spielers in einer Welt. */
    public record Bonus(int points, long atMillis) {}

    public static final ScoreData EMPTY = new ScoreData(Map.of(), Map.of(), Map.of(), Map.of());

    private final Map<String, Set<UUID>> decosByWorld;
    private final Map<UUID, Map<UUID, Long>> findsByPlayer;
    private final Map<String, Map<UUID, Bonus>> bonusByWorld;
    private final Map<UUID, String> names;
    private final Map<String, Leaderboard> leaderboards = new ConcurrentHashMap<>();

    /**
     * @param decosByWorld  Welt -> Deko, die dort stehen
     * @param findsByPlayer Spieler -> gefundene Deko -> Zeitpunkt (Epoch-Millis)
     * @param bonusByWorld  Welt -> Spieler -> Korrektur
     * @param names         Spieler -> Name
     */
    public ScoreData(Map<String, Set<UUID>> decosByWorld, Map<UUID, Map<UUID, Long>> findsByPlayer,
                     Map<String, Map<UUID, Bonus>> bonusByWorld, Map<UUID, String> names) {
        Map<String, Set<UUID>> decos = new HashMap<>();
        decosByWorld.forEach((world, ids) -> decos.computeIfAbsent(key(world), k -> new HashSet<>()).addAll(ids));
        decos.replaceAll((world, ids) -> Set.copyOf(ids));
        this.decosByWorld = Map.copyOf(decos);
        Map<UUID, Map<UUID, Long>> finds = new HashMap<>();
        findsByPlayer.forEach((player, found) -> finds.put(player, Map.copyOf(found)));
        this.findsByPlayer = Map.copyOf(finds);
        Map<String, Map<UUID, Bonus>> bonus = new HashMap<>();
        bonusByWorld.forEach((world, perPlayer) -> bonus.computeIfAbsent(key(world), k -> new HashMap<>()).putAll(perPlayer));
        bonus.replaceAll((world, perPlayer) -> Map.copyOf(perPlayer));
        this.bonusByWorld = Map.copyOf(bonus);
        this.names = Map.copyOf(names);
    }

    private static String key(String world) {
        return world == null ? "" : world.toLowerCase(Locale.ROOT);
    }

    public Set<UUID> decosIn(String world) {
        return decosByWorld.getOrDefault(key(world), Set.of());
    }

    public int total(String world) {
        return decosIn(world).size();
    }

    /** Gefundene Deko des Spielers, die in dieser Welt stehen. */
    public int found(UUID playerId, String world) {
        Set<UUID> decos = decosIn(world);
        return (int) findsByPlayer.getOrDefault(playerId, Map.of()).keySet().stream().filter(decos::contains).count();
    }

    public Leaderboard leaderboard(String world) {
        return leaderboards.computeIfAbsent(key(world), this::computeLeaderboard);
    }

    private Leaderboard computeLeaderboard(String world) {
        Set<UUID> decos = decosIn(world);
        Map<UUID, Bonus> bonus = bonusByWorld.getOrDefault(world, Map.of());
        Set<UUID> players = new HashSet<>(findsByPlayer.keySet());
        players.addAll(bonus.keySet());
        List<Leaderboard.Entry> entries = new ArrayList<>();
        for (UUID player : players) {
            int score = 0;
            long last = 0L;
            for (Map.Entry<UUID, Long> find : findsByPlayer.getOrDefault(player, Map.of()).entrySet()) {
                if (decos.contains(find.getKey())) {
                    score++;
                    last = Math.max(last, find.getValue());
                }
            }
            Bonus extra = bonus.get(player);
            if (extra != null) {
                score += extra.points();
                last = Math.max(last, extra.atMillis());
            }
            entries.add(new Leaderboard.Entry(player, names.getOrDefault(player, "?"), score, last));
        }
        return Leaderboard.of(entries);
    }

    public ScoreData withFind(UUID playerId, String name, UUID decoId, long atMillis) {
        Map<UUID, Map<UUID, Long>> finds = new HashMap<>(findsByPlayer);
        Map<UUID, Long> found = new HashMap<>(finds.getOrDefault(playerId, Map.of()));
        found.putIfAbsent(decoId, atMillis);
        finds.put(playerId, found);
        Map<UUID, String> allNames = new HashMap<>(names);
        allNames.put(playerId, name);
        return new ScoreData(decosByWorld, finds, bonusByWorld, allNames);
    }

    /** Deko steht (auch) in dieser Welt - z.B. in einer Kopie entdeckt. */
    public ScoreData withDecoIn(UUID decoId, String world) {
        if (decosIn(world).contains(decoId)) {
            return this;
        }
        Map<String, Set<UUID>> decos = new HashMap<>(decosByWorld);
        Set<UUID> ids = new HashSet<>(decos.getOrDefault(key(world), Set.of()));
        ids.add(decoId);
        decos.put(key(world), ids);
        return new ScoreData(decos, findsByPlayer, bonusByWorld, names);
    }

    public ScoreData withoutDeco(UUID decoId) {
        Map<String, Set<UUID>> decos = new HashMap<>();
        decosByWorld.forEach((world, ids) -> {
            Set<UUID> rest = new HashSet<>(ids);
            rest.remove(decoId);
            decos.put(world, rest);
        });
        return new ScoreData(decos, findsByPlayer, bonusByWorld, names);
    }
}
