package dev.saseq.events;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.util.json.JsonParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * SQLite event store (WAL). Writes are serialized on one background thread so JDA
 * gateway threads never block. Readers open their own short-lived connections.
 */
@Component
public class EventStore {
    private static final Logger log = LoggerFactory.getLogger(EventStore.class);

    private final String dbPath;
    private final int retentionDays;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "event-store-writer");
        t.setDaemon(true);
        return t;
    });

    public EventStore(@Value("${EVENTS_DB_PATH:/data/events.db}") String dbPath,
                      @Value("${EVENT_RETENTION_DAYS:14}") int retentionDays) {
        this.dbPath = dbPath;
        this.retentionDays = retentionDays;
        initSchema();
    }

    private void initSchema() {
        try {
            Path parent = Path.of(dbPath).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String ddl = new ClassPathResource("db/schema.sql").getContentAsString(StandardCharsets.UTF_8);
            try (Connection c = openConnection(); Statement st = c.createStatement()) {
                for (String sql : ddl.replaceAll("(?m)--.*$", "").split(";")) {
                    if (!sql.isBlank()) {
                        st.execute(sql);
                    }
                }
            }
            log.info("Event store ready at {} (retention {} days)", dbPath, retentionDays);
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Cannot initialize event store at " + dbPath, e);
        }
    }

    /** New connection with a busy timeout; caller closes it. */
    public Connection openConnection() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }

    public int retentionDays() {
        return retentionDays;
    }

    /** Queue an event for insertion; never blocks the caller. */
    public Future<?> record(EventRecord e) {
        return writer.submit(() -> insert(e));
    }

    /** Run arbitrary write work on the single writer thread (e.g. pending_actions updates). */
    public <T> Future<T> submitWrite(java.util.concurrent.Callable<T> work) {
        return writer.submit(work);
    }

    private void insert(EventRecord e) {
        String sql = "INSERT INTO events(ts,type,guild_id,channel_id,user_id,user_name,payload) VALUES(?,?,?,?,?,?,?)";
        try (Connection c = openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
            ps.setString(2, e.type());
            ps.setString(3, e.guildId());
            ps.setString(4, e.channelId());
            ps.setString(5, e.userId());
            ps.setString(6, e.userName());
            ps.setString(7, JsonParser.toJson(e.payload() == null ? Map.of() : e.payload()));
            ps.executeUpdate();
        } catch (SQLException ex) {
            log.warn("Failed to store event type={}: {}", e.type(), ex.getMessage());
        }
    }

    /** Delete events older than the retention window. Returns rows removed. */
    public int prune() {
        String cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS).toString();
        try (Connection c = openConnection(); PreparedStatement ps = c.prepareStatement("DELETE FROM events WHERE ts < ?")) {
            ps.setString(1, cutoff);
            int n = ps.executeUpdate();
            log.info("Pruned {} events older than {} days", n, retentionDays);
            return n;
        } catch (SQLException ex) {
            log.warn("Event prune failed: {}", ex.getMessage());
            return 0;
        }
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        writer.shutdown();
        writer.awaitTermination(5, TimeUnit.SECONDS);
    }
}
