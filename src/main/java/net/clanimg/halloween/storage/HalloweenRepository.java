package net.clanimg.halloween.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Logger;
import net.clanimg.halloween.HalloweenSettings.DatabaseSettings;
import net.clanimg.halloween.deco.Deco;
import net.clanimg.halloween.score.ScoreData;

/**
 * MariaDB-Zugriff. Alle Methoden blockieren - nur außerhalb des Server-Threads aufrufen.
 * Alles hängt an der event-id aus der Config.
 */
public final class HalloweenRepository implements AutoCloseable {
    private static final String[] SCHEMA = {
        """
        CREATE TABLE IF NOT EXISTS halloween_items (
            event_id VARCHAR(64) NOT NULL,
            slot INT NOT NULL,
            item MEDIUMBLOB NOT NULL,
            PRIMARY KEY (event_id, slot)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS halloween_decos (
            deco_id CHAR(36) PRIMARY KEY,
            event_id VARCHAR(64) NOT NULL,
            world VARCHAR(64) NOT NULL,
            x INT NOT NULL,
            y INT NOT NULL,
            z INT NOT NULL,
            material VARCHAR(64) NOT NULL,
            tile BOOLEAN NOT NULL,
            placed_by CHAR(36) NULL,
            placed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            INDEX idx_halloween_decos_event (event_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS halloween_finds (
            event_id VARCHAR(64) NOT NULL,
            player_uuid CHAR(36) NOT NULL,
            deco_id CHAR(36) NOT NULL,
            found_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
            PRIMARY KEY (event_id, player_uuid, deco_id),
            INDEX idx_halloween_finds_deco (deco_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS halloween_deco_worlds (
            event_id VARCHAR(64) NOT NULL,
            deco_id CHAR(36) NOT NULL,
            world VARCHAR(64) NOT NULL,
            PRIMARY KEY (event_id, deco_id, world)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS halloween_world_bonus (
            event_id VARCHAR(64) NOT NULL,
            world VARCHAR(64) NOT NULL,
            player_uuid CHAR(36) NOT NULL,
            bonus INT NOT NULL DEFAULT 0,
            updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
            PRIMARY KEY (event_id, world, player_uuid)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS halloween_players (
            player_uuid CHAR(36) PRIMARY KEY,
            name VARCHAR(16) NOT NULL,
            updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        )
        """,
    };

    private final HikariDataSource pool;
    private final String eventId;

    private HalloweenRepository(HikariDataSource pool, String eventId) {
        this.pool = pool;
        this.eventId = eventId;
    }

    /** Verbindet sich und legt fehlende Tabellen an. */
    public static HalloweenRepository connect(DatabaseSettings settings, String eventId, Logger logger) throws SQLException {
        if (settings.password() != null && settings.password().contains("{{")) {
            logger.warning("database.password ist noch ein SimpleCloud-Platzhalter. SimpleCloud setzt ihn nur in Dateien ein, "
                + "die es aus dem Template kopiert - config.yml ins Template legen (plugins/Halloween/config.yml).");
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("Halloween");
        config.setDriverClassName("org.mariadb.jdbc.Driver");
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.user());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.poolSize());
        config.setConnectionTimeout(5_000L);
        HikariDataSource pool = new HikariDataSource(config);
        HalloweenRepository repository = new HalloweenRepository(pool, eventId);
        try {
            repository.createSchema();
        } catch (SQLException ex) {
            pool.close();
            throw ex;
        }
        return repository;
    }

    private void createSchema() throws SQLException {
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : SCHEMA) {
                statement.execute(sql);
            }
        }
    }

    // ------------------------------------------------------------------ Deko-Items (/halloween setup)

    /** Gespeicherte Items je Slot (serialisierte ItemStacks). */
    public Map<Integer, byte[]> loadItems() throws SQLException {
        Map<Integer, byte[]> items = new TreeMap<>();
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT slot, item FROM halloween_items WHERE event_id = ?")) {
            statement.setString(1, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    items.put(rows.getInt("slot"), rows.getBytes("item"));
                }
            }
        }
        return items;
    }

    public void saveItems(Map<Integer, byte[]> items) throws SQLException {
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM halloween_items WHERE event_id = ?");
                 PreparedStatement insert = connection.prepareStatement("INSERT INTO halloween_items (event_id, slot, item) VALUES (?, ?, ?)")) {
                delete.setString(1, eventId);
                delete.executeUpdate();
                for (Map.Entry<Integer, byte[]> item : items.entrySet()) {
                    insert.setString(1, eventId);
                    insert.setInt(2, item.getKey());
                    insert.setBytes(3, item.getValue());
                    insert.addBatch();
                }
                insert.executeBatch();
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            }
        }
    }

    // ------------------------------------------------------------------ Deko

    public List<Deco> loadDecos() throws SQLException {
        List<Deco> decos = new ArrayList<>();
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT deco_id, world, x, y, z, material, tile FROM halloween_decos WHERE event_id = ?")) {
            statement.setString(1, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    decos.add(new Deco(UUID.fromString(rows.getString("deco_id")), rows.getString("world"),
                        rows.getInt("x"), rows.getInt("y"), rows.getInt("z"), rows.getString("material"), rows.getBoolean("tile")));
                }
            }
        }
        return decos;
    }

    public void addDeco(Deco deco, UUID placedBy) throws SQLException {
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO halloween_decos (deco_id, event_id, world, x, y, z, material, tile, placed_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, deco.id().toString());
            statement.setString(2, eventId);
            statement.setString(3, deco.world());
            statement.setInt(4, deco.x());
            statement.setInt(5, deco.y());
            statement.setInt(6, deco.z());
            statement.setString(7, deco.material());
            statement.setBoolean(8, deco.tile());
            statement.setString(9, placedBy == null ? null : placedBy.toString());
            statement.executeUpdate();
        }
        addDecoWorld(deco.id(), deco.world());
    }

    /** Entfernt die Deko und alle Funde davon (sonst könnte man eine woanders neu gesetzte Deko doppelt zählen). */
    public void removeDeco(UUID decoId) throws SQLException {
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement finds = connection.prepareStatement("DELETE FROM halloween_finds WHERE event_id = ? AND deco_id = ?");
                 PreparedStatement worlds = connection.prepareStatement("DELETE FROM halloween_deco_worlds WHERE event_id = ? AND deco_id = ?");
                 PreparedStatement deco = connection.prepareStatement("DELETE FROM halloween_decos WHERE event_id = ? AND deco_id = ?")) {
                finds.setString(1, eventId);
                finds.setString(2, decoId.toString());
                finds.executeUpdate();
                worlds.setString(1, eventId);
                worlds.setString(2, decoId.toString());
                worlds.executeUpdate();
                deco.setString(1, eventId);
                deco.setString(2, decoId.toString());
                deco.executeUpdate();
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            }
        }
    }

    // ------------------------------------------------------------------ Funde

    public Set<UUID> loadFound(UUID playerId) throws SQLException {
        Set<UUID> found = new HashSet<>();
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT deco_id FROM halloween_finds WHERE event_id = ? AND player_uuid = ?")) {
            statement.setString(1, eventId);
            statement.setString(2, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.add(UUID.fromString(rows.getString("deco_id")));
                }
            }
        }
        return found;
    }

    /** Funde mehrerer Spieler auf einmal (für den regelmäßigen Abgleich der Online-Spieler). */
    public Map<UUID, Set<UUID>> loadFound(Collection<UUID> playerIds) throws SQLException {
        Map<UUID, Set<UUID>> found = new HashMap<>();
        if (playerIds.isEmpty()) {
            return found;
        }
        playerIds.forEach(id -> found.put(id, new HashSet<>()));
        String placeholders = String.join(",", java.util.Collections.nCopies(playerIds.size(), "?"));
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT player_uuid, deco_id FROM halloween_finds WHERE event_id = ? AND player_uuid IN (" + placeholders + ")")) {
            statement.setString(1, eventId);
            int index = 2;
            for (UUID id : playerIds) {
                statement.setString(index++, id.toString());
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.get(UUID.fromString(rows.getString("player_uuid"))).add(UUID.fromString(rows.getString("deco_id")));
                }
            }
        }
        return found;
    }

    /** @return false, wenn der Spieler die Deko schon hatte (z.B. auf einem anderen Server gefunden) */
    public boolean recordFind(UUID playerId, String playerName, UUID decoId) throws SQLException {
        try (Connection connection = pool.getConnection()) {
            try (PreparedStatement name = connection.prepareStatement(
                "INSERT INTO halloween_players (player_uuid, name) VALUES (?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name)")) {
                name.setString(1, playerId.toString());
                name.setString(2, playerName);
                name.executeUpdate();
            }
            try (PreparedStatement find = connection.prepareStatement(
                "INSERT IGNORE INTO halloween_finds (event_id, player_uuid, deco_id) VALUES (?, ?, ?)")) {
                find.setString(1, eventId);
                find.setString(2, playerId.toString());
                find.setString(3, decoId.toString());
                return find.executeUpdate() == 1;
            }
        }
    }

    /** Hält den Namen aktuell, falls der Spieler schon in der Wertung steht (Namensänderung). */
    public void updatePlayerName(UUID playerId, String playerName) throws SQLException {
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE halloween_players SET name = ? WHERE player_uuid = ?")) {
            statement.setString(1, playerName);
            statement.setString(2, playerId.toString());
            statement.executeUpdate();
        }
    }

    /** Deko steht (auch) in dieser Welt - z.B. in einer kopierten Welt entdeckt. */
    public void addDecoWorld(UUID decoId, String world) throws SQLException {
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT IGNORE INTO halloween_deco_worlds (event_id, deco_id, world) VALUES (?, ?, ?)")) {
            statement.setString(1, eventId);
            statement.setString(2, decoId.toString());
            statement.setString(3, world.toLowerCase(Locale.ROOT));
            statement.executeUpdate();
        }
    }

    /**
     * Stand für die Wertung pro Welt: welche Deko in welcher Welt steht (die Welt, in der sie gesetzt wurde, plus
     * entdeckte Kopien), alle Funde, Korrekturen je Welt und Namen.
     */
    public ScoreData loadScoreData(List<Deco> decos) throws SQLException {
        Map<String, Set<UUID>> decosByWorld = new HashMap<>();
        Set<UUID> existing = new HashSet<>();
        for (Deco deco : decos) {
            existing.add(deco.id());
            decosByWorld.computeIfAbsent(deco.world().toLowerCase(Locale.ROOT), k -> new HashSet<>()).add(deco.id());
        }
        Map<UUID, Map<UUID, Long>> finds = new HashMap<>();
        Map<String, Map<UUID, ScoreData.Bonus>> bonus = new HashMap<>();
        Map<UUID, String> names = new HashMap<>();
        try (Connection connection = pool.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT deco_id, world FROM halloween_deco_worlds WHERE event_id = ?")) {
                statement.setString(1, eventId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        UUID decoId = UUID.fromString(rows.getString("deco_id"));
                        if (existing.contains(decoId)) {
                            decosByWorld.computeIfAbsent(rows.getString("world").toLowerCase(Locale.ROOT), k -> new HashSet<>()).add(decoId);
                        }
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid, deco_id, found_at FROM halloween_finds WHERE event_id = ?")) {
                statement.setString(1, eventId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        UUID decoId = UUID.fromString(rows.getString("deco_id"));
                        if (existing.contains(decoId)) {
                            Timestamp at = rows.getTimestamp("found_at");
                            finds.computeIfAbsent(UUID.fromString(rows.getString("player_uuid")), k -> new HashMap<>())
                                .put(decoId, at == null ? 0L : at.getTime());
                        }
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT world, player_uuid, bonus, updated_at FROM halloween_world_bonus WHERE event_id = ?")) {
                statement.setString(1, eventId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Timestamp at = rows.getTimestamp("updated_at");
                        bonus.computeIfAbsent(rows.getString("world").toLowerCase(Locale.ROOT), k -> new HashMap<>())
                            .put(UUID.fromString(rows.getString("player_uuid")),
                                new ScoreData.Bonus(rows.getInt("bonus"), at == null ? 0L : at.getTime()));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT player_uuid, name FROM halloween_players");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    names.put(UUID.fromString(rows.getString("player_uuid")), rows.getString("name"));
                }
            }
        }
        return new ScoreData(decosByWorld, finds, bonus, names);
    }

    // ------------------------------------------------------------------ Punkte von Hand (/halloween set|add|remove)

    public enum Adjustment { SET, ADD, REMOVE }

    /**
     * Setzt die Punkte eines Spielers in einer Welt bzw. zählt dazu/ab (nie unter 0). Gespeichert wird nur die Korrektur
     * zu den in dieser Welt gefundenen Deko - weitere Funde zählen danach normal dazu. Bei 0 werden die Funde der Deko
     * dieser Welt gelöscht, damit man sie neu suchen kann. @return neue Punktzahl
     */
    public int adjustWorldScore(String world, Set<UUID> decosInWorld, UUID playerId, String playerName,
                                Adjustment adjustment, int amount) throws SQLException {
        String worldKey = world.toLowerCase(Locale.ROOT);
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Set<UUID> found = new HashSet<>();
                try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT deco_id FROM halloween_finds WHERE event_id = ? AND player_uuid = ? FOR UPDATE")) {
                    statement.setString(1, eventId);
                    statement.setString(2, playerId.toString());
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            UUID decoId = UUID.fromString(rows.getString("deco_id"));
                            if (decosInWorld.contains(decoId)) {
                                found.add(decoId);
                            }
                        }
                    }
                }
                int bonus = 0;
                try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT bonus FROM halloween_world_bonus WHERE event_id = ? AND world = ? AND player_uuid = ? FOR UPDATE")) {
                    statement.setString(1, eventId);
                    statement.setString(2, worldKey);
                    statement.setString(3, playerId.toString());
                    try (ResultSet rows = statement.executeQuery()) {
                        bonus = rows.next() ? rows.getInt(1) : 0;
                    }
                }
                int current = found.size() + bonus;
                int target = Math.max(0, switch (adjustment) {
                    case SET -> amount;
                    case ADD -> current + amount;
                    case REMOVE -> current - amount;
                });
                if (target == 0 && !found.isEmpty()) {
                    // Auf 0 gesetzt = in dieser Welt neu anfangen: Funde ihrer Deko löschen.
                    try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM halloween_finds WHERE event_id = ? AND player_uuid = ? AND deco_id = ?")) {
                        for (UUID decoId : found) {
                            delete.setString(1, eventId);
                            delete.setString(2, playerId.toString());
                            delete.setString(3, decoId.toString());
                            delete.addBatch();
                        }
                        delete.executeBatch();
                    }
                    found.clear();
                }
                try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO halloween_world_bonus (event_id, world, player_uuid, bonus) VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE bonus = VALUES(bonus)
                    """)) {
                    upsert.setString(1, eventId);
                    upsert.setString(2, worldKey);
                    upsert.setString(3, playerId.toString());
                    upsert.setInt(4, target - found.size());
                    upsert.executeUpdate();
                }
                try (PreparedStatement name = connection.prepareStatement(
                    "INSERT INTO halloween_players (player_uuid, name) VALUES (?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name)")) {
                    name.setString(1, playerId.toString());
                    name.setString(2, playerName);
                    name.executeUpdate();
                }
                connection.commit();
                return target;
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            }
        }
    }

    /** Alle Spieler, die schon in der Wertung waren (Name -> UUID). */
    public Map<String, UUID> knownPlayers() throws SQLException {
        Map<String, UUID> players = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT player_uuid, name FROM halloween_players")) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    players.put(rows.getString("name"), UUID.fromString(rows.getString("player_uuid")));
                }
            }
        }
        return players;
    }

    @Override
    public void close() {
        pool.close();
    }
}
