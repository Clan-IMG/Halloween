package net.clanimg.halloween.score;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScoreDataTest {
    private final UUID head1 = UUID.randomUUID();
    private final UUID head2 = UUID.randomUUID();
    private final UUID oldHead = UUID.randomUUID();
    private final UUID me = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    private ScoreData data() {
        return new ScoreData(
            Map.of("Halloween-Map", Set.of(head1, head2), "geloeschte-Welt", Set.of(oldHead)),
            Map.of(me, Map.of(head1, 10L, oldHead, 5L), other, Map.of(head1, 3L, head2, 4L)),
            Map.of("halloween-map", Map.of(me, new ScoreData.Bonus(5, 20L))),
            Map.of(me, "WrobelXXL", other, "SkyCryGaming"));
    }

    @Test
    void onlyDecoOfThisWorldCount() {
        ScoreData data = data();

        assertEquals(2, data.total("halloween-map"));
        assertEquals(1, data.found(me, "Halloween-Map"), "Deko aus der gelöschten Welt zählt hier nicht");
        assertEquals(2, data.leaderboard("Halloween-Map").entry(me).orElseThrow().score(), "1 Fund + 5 Korrektur, gedeckelt auf 2 Deko");
        assertEquals(2, data.leaderboard("Halloween-Map").entry(other).orElseThrow().score());
        assertEquals(0, data.total("lobby"));
    }

    @Test
    void decoSeenInACopyCountsThereToo() {
        ScoreData data = data().withDecoIn(head1, "Kopie").withFind(other, "SkyCryGaming", head1, 30L);

        assertEquals(1, data.total("Kopie"));
        assertEquals(1, data.found(other, "Kopie"));
        assertEquals(1, data.leaderboard("Kopie").entry(me).orElseThrow().score(), "head1 hatte ich schon im Original gefunden");
    }

    @Test
    void scoreNeverExceedsTheDecoOfTheWorld() {
        // set 2 = alle gefunden; ein weiterer echter Fund darf nicht 3/2 ergeben
        ScoreData data = new ScoreData(Map.of("map", Set.of(head1, head2)), Map.of(me, Map.of(head1, 1L)),
            Map.of("map", Map.of(me, new ScoreData.Bonus(2, 2L))), Map.of(me, "WrobelXXL"));

        assertEquals(2, data.score(me, "map"));
        assertEquals(2, data.withFind(me, "WrobelXXL", head2, 3L).score(me, "map"));
    }

    @Test
    void newFindAndRemovedDecoUpdateTheWorld() {
        ScoreData data = data().withFind(me, "WrobelXXL", head2, 40L).withoutDeco(head1);

        assertEquals(1, data.total("Halloween-Map"));
        assertEquals(1, data.found(me, "Halloween-Map"));
        assertEquals(1, data.found(other, "Halloween-Map"));
    }
}
