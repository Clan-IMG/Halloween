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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Logger;
import net.clanimg.halloween.HalloweenSettings.DatabaseSettings;
import net.clanimg.halloween.deco.Deco;
import net.clanimg.halloween.score.Leaderboard;

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
        CREATE TABLE IF NOT EXISTS halloween_adjustments (
            event_id VARCHAR(64) NOT NULL,
            player_uuid CHAR(36) NOT NULL,
            bonus INT NOT NULL DEFAULT 0,
            updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
            PRIMARY KEY (event_id, player_uuid)
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
    }

    /** Entfernt die Deko und alle Funde davon (sonst könnte man eine woanders neu gesetzte Deko doppelt zählen). */
    public void removeDeco(UUID decoId) throws SQLException {
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement finds = connection.prepareStatement("DELETE FROM halloween_finds WHERE event_id = ? AND deco_id = ?");
                 PreparedStatement deco = connection.prepareStatement("DELETE FROM halloween_decos WHERE event_id = ? AND deco_id = ?")) {
                finds.setString(1, eventId);
                finds.setString(2, decoId.toString());
                finds.executeUpdate();
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

    /** Punkte aller Spieler: gefundene Deko (die es im Event noch gibt) plus Korrektur per /halloween set|add|remove. */
    public List<Leaderboard.Entry> loadScores() throws SQLException {
        List<Leaderboard.Entry> entries = new ArrayList<>();
        try (Connection connection = pool.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT s.player_uuid, COALESCE(p.name, '?') AS name, SUM(s.points) AS score, MAX(s.at) AS last_found
                 FROM (
                     SELECT f.player_uuid, COUNT(*) AS points, MAX(f.found_at) AS at
                     FROM halloween_finds f
                     JOIN halloween_decos d ON d.deco_id = f.deco_id AND d.event_id = f.event_id
                     WHERE f.event_id = ?
                     GROUP BY f.player_uuid
                     UNION ALL
                     SELECT a.player_uuid, a.bonus, a.updated_at FROM halloween_adjustments a WHERE a.event_id = ?
                 ) s
                 LEFT JOIN halloween_players p ON p.player_uuid = s.player_uuid
                 GROUP BY s.player_uuid, p.name
                 """)) {
            statement.setString(1, eventId);
            statement.setString(2, eventId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Timestamp last = rows.getTimestamp("last_found");
                    entries.add(new Leaderboard.Entry(UUID.fromString(rows.getString("player_uuid")), rows.getString("name"),
                        rows.getInt("score"), last == null ? 0L : last.getTime()));
                }
            }
        }
        return entries;
    }

    // ------------------------------------------------------------------ Punkte von Hand (/halloween set|add|remove)

    public enum Adjustment { SET, ADD, REMOVE }

    /**
     * Setzt die Punkte eines Spielers bzw. zählt dazu/ab (nie unter 0). Gespeichert wird nur die Korrektur zu den
     * gefundenen Deko - weitere Funde zählen danach normal dazu. @return neue Punktzahl
     */
    public int adjustScore(UUID playerId, String playerName, Adjustment adjustment, int amount) throws SQLException {
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int found = queryInt(connection, """
                    SELECT COUNT(*) FROM halloween_finds f
                    JOIN halloween_decos d ON d.deco_id = f.deco_id AND d.event_id = f.event_id
                    WHERE f.event_id = ? AND f.player_uuid = ?
                    """, playerId);
                int bonus = queryInt(connection,
                    "SELECT COALESCE(MAX(bonus), 0) FROM halloween_adjustments WHERE event_id = ? AND player_uuid = ? FOR UPDATE", playerId);
                int current = found + bonus;
                int target = switch (adjustment) {
                    case SET -> amount;
                    case ADD -> current + amount;
                    case REMOVE -> current - amount;
                };
                target = Math.max(0, target);
                if (target == 0) {
                    // Auf 0 gesetzt = neu anfangen: auch die Funde löschen, sonst wäre jede Deko "schon gefunden".
                    try (PreparedStatement finds = connection.prepareStatement(
                        "DELETE FROM halloween_finds WHERE event_id = ? AND player_uuid = ?")) {
                        finds.setString(1, eventId);
                        finds.setString(2, playerId.toString());
                        finds.executeUpdate();
                    }
                    found = 0;
                }
                try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO halloween_adjustments (event_id, player_uuid, bonus) VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE bonus = VALUES(bonus)
                    """)) {
                    upsert.setString(1, eventId);
                    upsert.setString(2, playerId.toString());
                    upsert.setInt(3, target - found);
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

    private int queryInt(Connection connection, String sql, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, eventId);
            statement.setString(2, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
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
