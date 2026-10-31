package net.clanimg.halloween.score;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Rangliste: mehr gefundene Deko zuerst; bei Gleichstand, wer den Stand früher erreicht hat.
 * Unveränderlich - Änderungen liefern eine neue Rangliste.
 */
public final class Leaderboard {
    public record Entry(UUID playerId, String name, int score, long lastFoundMillis) {}

    private static final Comparator<Entry> ORDER = Comparator.comparingInt(Entry::score).reversed()
        .thenComparingLong(Entry::lastFoundMillis)
        .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER);

    public static final Leaderboard EMPTY = new Leaderboard(List.of());

    private final List<Entry> ranked;
    private final Map<UUID, Integer> rankByPlayer;

    private Leaderboard(List<Entry> ranked) {
        this.ranked = ranked;
        this.rankByPlayer = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            rankByPlayer.put(ranked.get(i).playerId(), i + 1);
        }
    }

    public static Leaderboard of(Collection<Entry> entries) {
        List<Entry> sorted = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.score() > 0) {
                sorted.add(entry);
            }
        }
        sorted.sort(ORDER);
        return new Leaderboard(List.copyOf(sorted));
    }

    public List<Entry> top(int count) {
        return ranked.subList(0, Math.min(count, ranked.size()));
    }

    /** Platz ab 1, leer wenn der Spieler noch nichts gefunden hat. */
    public Optional<Integer> rank(UUID playerId) {
        return Optional.ofNullable(rankByPlayer.get(playerId));
    }

    public Optional<Entry> entry(UUID playerId) {
        Integer rank = rankByPlayer.get(playerId);
        return rank == null ? Optional.empty() : Optional.of(ranked.get(rank - 1));
    }

    /** Sofort sichtbarer Fund, bevor der nächste Abgleich mit der Datenbank kommt. */
    public Leaderboard withFind(UUID playerId, String name, long foundAtMillis) {
        List<Entry> entries = new ArrayList<>(ranked.size() + 1);
        boolean present = false;
        for (Entry entry : ranked) {
            if (entry.playerId().equals(playerId)) {
                entries.add(new Entry(playerId, name, entry.score() + 1, foundAtMillis));
                present = true;
            } else {
                entries.add(entry);
            }
        }
        if (!present) {
            entries.add(new Entry(playerId, name, 1, foundAtMillis));
        }
        return of(entries);
    }
}
