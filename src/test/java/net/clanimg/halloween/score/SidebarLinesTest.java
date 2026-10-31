package net.clanimg.halloween.score;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.clanimg.halloween.HalloweenSettings.ScoreboardSettings;
import org.junit.jupiter.api.Test;

class SidebarLinesTest {
    private static final ScoreboardSettings SETTINGS = new ScoreboardSettings(true, "Titel", "Such die Helloween Deko.", 5,
        "%rank%. %name% (%score%)", "%rank%. %name% (%score%/%total%)", "%name% (%score%/%total%)", true, "Noch nichts", 10);

    private static List<Leaderboard.Entry> players(int count) {
        List<Leaderboard.Entry> entries = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            entries.add(new Leaderboard.Entry(UUID.randomUUID(), "Spieler" + i, 30 - i, i));
        }
        return entries;
    }

    @Test
    void mostPointsFirstAndOnTieWhoGotThereEarlier() {
        UUID a = UUID.randomUUID();
        Leaderboard board = Leaderboard.of(List.of(
            new Leaderboard.Entry(a, "Anna", 2, 2_000),
            new Leaderboard.Entry(UUID.randomUUID(), "Ben", 3, 5_000),
            new Leaderboard.Entry(UUID.randomUUID(), "Cem", 2, 1_000)));

        assertEquals(List.of("Ben", "Cem", "Anna"), board.top(5).stream().map(Leaderboard.Entry::name).toList());
        assertEquals(3, board.rank(a).orElseThrow());
    }

    @Test
    void numberedTopFiveAndOwnLineWithTotalBelow() {
        List<Leaderboard.Entry> entries = players(11);
        UUID me = entries.get(10).playerId();

        assertEquals(List.of("Such die Helloween Deko.", "",
                "1. Spieler1 (29)", "2. Spieler2 (28)", "3. Spieler3 (27)", "4. Spieler4 (26)", "5. Spieler5 (25)",
                "", "11. Spieler11 (19/20)"),
            SidebarLines.build(Leaderboard.of(entries), me, "Spieler11", 20, SETTINGS));
    }

    @Test
    void ownLineInsideTopFiveShowsTotalThere() {
        List<Leaderboard.Entry> entries = players(5);
        UUID me = entries.get(3).playerId();

        assertEquals(List.of("Such die Helloween Deko.", "",
                "1. Spieler1 (29)", "2. Spieler2 (28)", "3. Spieler3 (27)", "4. Spieler4 (26/40)", "5. Spieler5 (25)"),
            SidebarLines.build(Leaderboard.of(entries), me, "Spieler4", 40, SETTINGS));
    }

    @Test
    void withoutPointsTheOwnLineHasNoRank() {
        assertEquals(List.of("Such die Helloween Deko.", "", "Noch nichts", "", "Neu (0/20)"),
            SidebarLines.build(Leaderboard.EMPTY, UUID.randomUUID(), "Neu", 20, SETTINGS));
    }

    @Test
    void aNewFindMovesThePlayerUpImmediately() {
        UUID me = UUID.randomUUID();
        Leaderboard board = Leaderboard.of(List.of(new Leaderboard.Entry(UUID.randomUUID(), "Ben", 1, 1)))
            .withFind(me, "WrobelXXL", 2).withFind(me, "WrobelXXL", 3);

        assertEquals(List.of("WrobelXXL", "Ben"), board.top(5).stream().map(Leaderboard.Entry::name).toList());
    }
}
