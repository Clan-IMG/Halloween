package net.clanimg.halloween.score;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.clanimg.halloween.HalloweenSettings.ScoreboardSettings;

/** Zeilen der Sidebar für einen Spieler (von oben nach unten), noch mit &-Farbcodes. */
public final class SidebarLines {
    private SidebarLines() {
    }

    public static List<String> build(Leaderboard leaderboard, UUID viewerId, String viewerName, ScoreboardSettings settings) {
        List<String> lines = new ArrayList<>();
        if (settings.subtitle() != null && !settings.subtitle().isBlank()) {
            lines.add(settings.subtitle());
            lines.add("");
        }

        List<Leaderboard.Entry> top = leaderboard.top(settings.top());
        if (top.isEmpty() && settings.empty() != null && !settings.empty().isBlank()) {
            lines.add(settings.empty());
        }
        for (int i = 0; i < top.size(); i++) {
            Leaderboard.Entry entry = top.get(i);
            lines.add(format(settings.entry(), i + 1, entry.name(), entry.score()));
        }

        Optional<Integer> rank = leaderboard.rank(viewerId);
        boolean inTop = rank.isPresent() && rank.get() <= settings.top();
        if (!inTop && (rank.isPresent() || settings.showOwnWithoutScore())) {
            int score = leaderboard.entry(viewerId).map(Leaderboard.Entry::score).orElse(0);
            lines.add("");
            lines.add(format(settings.ownEntry(), rank.orElse(0), viewerName, score));
        }
        return lines;
    }

    private static String format(String template, int rank, String name, int score) {
        return template
            .replace("%rank%", rank > 0 ? Integer.toString(rank) : "-")
            .replace("%name%", name)
            .replace("%score%", Integer.toString(score));
    }
}
