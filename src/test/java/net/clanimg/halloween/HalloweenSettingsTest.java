package net.clanimg.halloween;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class HalloweenSettingsTest {
    private static final Map<String, String> ENV = Map.of("HALLOWEEN_DB_PASSWORD", "geheim");

    @Test
    void unresolvedSimpleCloudPlaceholderFallsBackToEnvironment() {
        assertEquals("geheim", HalloweenSettings.resolve("{{ secrets.HALLOWEEN_DB_PASSWORD }}", ENV::get));
        assertEquals("geheim", HalloweenSettings.resolve("{{env.HALLOWEEN_DB_PASSWORD}}", ENV::get));
    }

    @Test
    void plainValuesAndUnknownPlaceholdersStayAsTheyAre() {
        assertEquals("klartext", HalloweenSettings.resolve("klartext", ENV::get));
        assertEquals("{{ secrets.GIBTS_NICHT }}", HalloweenSettings.resolve("{{ secrets.GIBTS_NICHT }}", ENV::get));
    }
}
