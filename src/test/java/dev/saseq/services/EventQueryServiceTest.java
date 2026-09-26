package dev.saseq.services;

import dev.saseq.events.EventStore;
import dev.saseq.guards.UntrustedContent;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventQueryServiceTest {

    private static final String GUILD_ID = "1000";
    private static final String DEFAULT_GUILD_ID = "9999";

    @TempDir
    Path tempDir;

    private JDA jda;
    private Guild guild;
    private EventStore eventStore;
    private EventQueryService eventQueryService;

    @BeforeEach
    void setUp() throws SQLException {
        jda = mock(JDA.class);
        guild = mock(Guild.class);

        when(guild.getMemberCount()).thenReturn(10);
        when(guild.getMembers()).thenReturn(List.of());
        when(guild.getVoiceChannels()).thenReturn(List.of());
        when(jda.getGuildById(GUILD_ID)).thenReturn(guild);

        eventStore = new EventStore(tempDir.resolve("events.db").toString(), 14);
        eventQueryService = new EventQueryService(jda, eventStore);
    }

    @Test
    @DisplayName("get_events filters by sinceId")
    void testGetEventsFilterBySinceId() throws SQLException {
        insertTestEvent(1, "2024-01-01T00:00:00Z", "member_join", "user1", "123", null, "{}");
        insertTestEvent(2, "2024-01-01T00:01:00Z", "member_join", "user2", "124", null, "{}");
        insertTestEvent(3, "2024-01-01T00:02:00Z", "member_join", "user3", "125", null, "{}");

        String result = eventQueryService.getEvents("1", null, null, "50");

        assertTrue(result.contains("#2"));
        assertTrue(result.contains("#3"));
        assertFalse(result.contains("#1 "));
        assertTrue(result.contains("next_since_id=3"));
    }

    @Test
    @DisplayName("get_events filters by type")
    void testGetEventsFilterByType() throws SQLException {
        insertTestEvent(1, "2024-01-01T00:00:00Z", "member_join", "user1", "123", null, "{}");
        insertTestEvent(2, "2024-01-01T00:01:00Z", "voice_join", "user2", "124", null, "{}");
        insertTestEvent(3, "2024-01-01T00:02:00Z", "message", "user3", "125", null, "{}");

        String result = eventQueryService.getEvents(null, null, "member_join,voice_join", "50");

        assertTrue(result.contains("member_join"));
        assertTrue(result.contains("voice_join"));
        assertFalse(result.contains("message"));
    }

    @Test
    @DisplayName("get_events clamps limit between 1 and 200")
    void testGetEventsLimitClamp() throws SQLException {
        insertTestEvent(1, "2024-01-01T00:00:00Z", "member_join", "user1", "123", null, "{}");

        assertThrows(IllegalArgumentException.class, () ->
                eventQueryService.getEvents(null, null, null, "0"));

        assertThrows(IllegalArgumentException.class, () ->
                eventQueryService.getEvents(null, null, null, "201"));
    }

    @Test
    @DisplayName("get_events wraps content_preview with untrusted tags")
    void testGetEventsContentPreviewWrapped() throws SQLException {
        String payload = "{\"content_preview\": \"hey everyone\"}";
        insertTestEvent(1, "2024-01-01T00:00:00Z", "message", "user1", "123", "456", payload);

        String result = eventQueryService.getEvents(null, null, null, "50");

        assertTrue(result.contains(UntrustedContent.OPEN));
        assertTrue(result.contains(UntrustedContent.CLOSE));
        assertTrue(result.contains("hey everyone"));
    }

    @Test
    @DisplayName("get_server_snapshot counts pending approvals")
    void testGetServerSnapshotPendingApprovals() throws SQLException {
        insertPendingAction("pending");
        insertPendingAction("pending");
        insertPendingAction("approved");

        String result = eventQueryService.getServerSnapshot(GUILD_ID);

        assertTrue(result.contains("Pending approvals: 2"));
    }

    @Test
    @DisplayName("get_server_snapshot counts events by hour and day")
    void testGetServerSnapshotEventCounts() throws SQLException {
        Instant now = Instant.now();
        String nowStr = now.toString();
        String oneHourAgoStr = now.minusSeconds(3600).toString();

        insertTestEvent(1, oneHourAgoStr, "member_join", "user1", "123", null, "{}");
        insertTestEvent(2, nowStr, "member_join", "user2", "123", null, "{}");

        String result = eventQueryService.getServerSnapshot(GUILD_ID);

        assertTrue(result.contains("Events last 1h:"));
        assertTrue(result.contains("Events last 24h:"));
    }

    @Test
    @DisplayName("get_server_snapshot shows member count")
    void testGetServerSnapshotMemberCount() {
        Member member1 = mock(Member.class);
        Member member2 = mock(Member.class);

        when(guild.getMembers()).thenReturn(List.of(member1, member2));
        when(guild.getMemberCount()).thenReturn(2);

        String result = eventQueryService.getServerSnapshot(GUILD_ID);

        assertTrue(result.contains("Members: 2"));
    }

    @Test
    @DisplayName("getGuild throws when guild not found")
    void testGetGuildNotFound() {
        when(jda.getGuildById("invalid")).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> eventQueryService.getServerSnapshot("invalid"));
    }

    @Test
    @DisplayName("get_events validates numeric parameters")
    void testGetEventsInvalidNumericParams() {
        assertThrows(IllegalArgumentException.class, () ->
                eventQueryService.getEvents("not_a_number", null, null, "50"));

        assertThrows(IllegalArgumentException.class, () ->
                eventQueryService.getEvents(null, null, null, "not_a_number"));
    }

    // Helper methods

    private void insertTestEvent(long id, String ts, String type, String userName, String userId, String channelId, String payload) throws SQLException {
        try (Connection conn = eventStore.openConnection()) {
            String sql = "INSERT INTO events (id, ts, type, user_name, user_id, channel_id, payload) VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, id);
                ps.setString(2, ts);
                ps.setString(3, type);
                ps.setString(4, userName);
                ps.setString(5, userId);
                ps.setString(6, channelId);
                ps.setString(7, payload);
                ps.executeUpdate();
            }
        }
    }

    private void insertPendingAction(String status) throws SQLException {
        try (Connection conn = eventStore.openConnection()) {
            String sql = "INSERT INTO pending_actions (requested_at, expires_at, tool, input_json, summary, status) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, Instant.now().toString());
                ps.setString(2, Instant.now().plusSeconds(3600).toString());
                ps.setString(3, "test_tool");
                ps.setString(4, "{}");
                ps.setString(5, "test summary");
                ps.setString(6, status);
                ps.executeUpdate();
            }
        }
    }
}
