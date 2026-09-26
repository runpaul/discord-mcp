package dev.saseq.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventStoreTest {

    @Test
    void record_insertsRowWithTypeAndPayloadJson(@TempDir Path tmp) throws Exception {
        EventStore store = new EventStore(tmp.resolve("e.db").toString(), 14);

        store.record(new EventRecord("member_join", "g1", "c1", "u1", "alice", Map.of("k", "v"))).get();

        try (Connection c = store.openConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT type, payload, guild_id, user_name FROM events")) {
            assertTrue(rs.next());
            assertEquals("member_join", rs.getString("type"));
            assertEquals("g1", rs.getString("guild_id"));
            assertEquals("alice", rs.getString("user_name"));
            assertTrue(rs.getString("payload").contains("\"k\":\"v\""));
            assertFalse(rs.next());
        }
    }

    @Test
    void prune_removesOldRows_keepsRecentRows(@TempDir Path tmp) throws Exception {
        EventStore store = new EventStore(tmp.resolve("e.db").toString(), 14);

        String oldTs = Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS).toString();
        try (Connection c = store.openConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO events(ts,type,guild_id,channel_id,user_id,user_name,payload) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, oldTs);
            ps.setString(2, "member_join");
            ps.setString(3, "g1");
            ps.setString(4, null);
            ps.setString(5, "u_old");
            ps.setString(6, "old");
            ps.setString(7, "{}");
            ps.executeUpdate();
        }

        store.record(new EventRecord("member_join", "g1", null, "u_new", "new", Map.of())).get();

        int removed = store.prune();
        assertEquals(1, removed);

        try (Connection c = store.openConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT user_id FROM events")) {
            assertTrue(rs.next());
            assertEquals("u_new", rs.getString("user_id"));
            assertFalse(rs.next());
        }
    }
}
