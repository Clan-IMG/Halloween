package net.clanimg.halloween.score;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.clanimg.halloween.HalloweenSettings.ScoreboardSettings;
import org.junit.jupiter.api.Test;

class SidebarLinesTest {
    private static final ScoreboardSettings SETTINGS = new ScoreboardSettings(true, "Titel", "&7Such die Helloween Deko.", 5,
        "&7%name% &8(&b%score%&8)", "&7%name% &8(&b%score%&8)", true, "&8Noch nichts gefunden", 10);

    @Test
    void mostFindsFirstAndOnTieWhoGotThereEarlier() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        Leaderboard board = Leaderboard.of(List.of(
            new Leaderboard.Entry(a, "Anna", 2, 2_000),
            new Leaderboard.Entry(b, "Ben", 3, 5_000),
            new Leaderboard.Entry(c, "Cem", 2, 1_000)));

        assertEquals(List.of("Ben", "Cem", "Anna"), board.top(5).stream().map(Leaderboard.Entry::name).toList());
        assertEquals(3, board.rank(a).orElseThrow());
    }

    @Test
    void showsTopFiveAndTheViewerSeparatelyWhenBelow() {
        List<Leaderboard.Entry> entries = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            entries.add(new Leaderboard.Entry(UUID.randomUUID(), "Spieler" + i, 10 - i, i));
        }
        UUID me = entries.get(6).playerId();

        List<String> lines = SidebarLines.build(Leaderboard.of(entries), me, "Spieler7", SETTINGS);

        assertEquals(List.of(
            "&7Such die Helloween Deko.", "",
            "&7Spieler1 &8(&b9&8)", "&7Spieler2 &8(&b8&8)", "&7Spieler3 &8(&b7&8)", "&7Spieler4 &8(&b6&8)", "&7Spieler5 &8(&b5&8)",
            "", "&7Spieler7 &8(&b3&8)"), lines);
    }

    @Test
    void viewerInTopFiveIsNotRepeated() {
        UUID me = UUID.randomUUID();
        Leaderboard board = Leaderboard.of(List.of(new Leaderboard.Entry(me, "WrobelXXL", 1, 1)));

        assertEquals(List.of("&7Such die Helloween Deko.", "", "&7WrobelXXL &8(&b1&8)"),
            SidebarLines.build(board, me, "WrobelXXL", SETTINGS));
    }

    @Test
    void aNewFindMovesThePlayerUpImmediately() {
        UUID me = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Leaderboard board = Leaderboard.of(List.of(new Leaderboard.Entry(other, "Ben", 1, 1)));

        List<String> before = SidebarLines.build(board, me, "WrobelXXL", SETTINGS);
        Leaderboard after = board.withFind(me, "WrobelXXL", 2).withFind(me, "WrobelXXL", 3);

        assertEquals(List.of("&7Such die Helloween Deko.", "", "&7Ben &8(&b1&8)", "", "&7WrobelXXL &8(&b0&8)"), before);
        assertEquals(List.of("WrobelXXL", "Ben"), after.top(5).stream().map(Leaderboard.Entry::name).toList());
    }

    @Test
    void emptyBoardShowsHint() {
        assertEquals(List.of("&7Such die Helloween Deko.", "", "&8Noch nichts gefunden", "", "&7Neu &8(&b0&8)"),
            SidebarLines.build(Leaderboard.EMPTY, UUID.randomUUID(), "Neu", SETTINGS));
    }
}
