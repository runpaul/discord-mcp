package dev.saseq.guards;

import dev.saseq.events.EventStore;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.SelfUser;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.MessageEditAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalServiceTest {

    private static final String CHANNEL_ID = "700";
    private static final String POSTED_MESSAGE_ID = "999";
    private static final String APPROVER = "approver1";
    private static final String OTHER_APPROVER = "approver2";
    private static final String NON_APPROVER = "intruder";
    private static final String BOT_ID = "bot-1";

    private JDA jda;
    private TextChannel channel;
    private Message posted;
    private GuildGuard guildGuard;
    private TargetGuard targetGuard;

    @BeforeEach
    void setUp() {
        jda = mock(JDA.class);
        channel = mock(TextChannel.class);
        when(jda.getTextChannelById(CHANNEL_ID)).thenReturn(channel);
        SelfUser selfUser = mock(SelfUser.class);
        when(selfUser.getId()).thenReturn(BOT_ID);
        when(jda.getSelfUser()).thenReturn(selfUser);

        posted = mock(Message.class);
        when(posted.getId()).thenReturn(POSTED_MESSAGE_ID);
        @SuppressWarnings("unchecked")
        MessageCreateAction createAction = mock(MessageCreateAction.class);
        when(channel.sendMessageEmbeds(any(MessageEmbed.class))).thenReturn(createAction);
        when(createAction.complete()).thenReturn(posted);

        @SuppressWarnings("unchecked")
        RestAction<Void> reactAction = mock(RestAction.class);
        when(posted.addReaction(any(Emoji.class))).thenReturn(reactAction);

        @SuppressWarnings("unchecked")
        RestAction<Message> retrieveAction = mock(RestAction.class);
        when(channel.retrieveMessageById(anyString())).thenReturn(retrieveAction);
        Message fetched = mock(Message.class);
        when(retrieveAction.complete()).thenReturn(fetched);
        when(fetched.getEmbeds()).thenReturn(List.of());
        MessageEditAction editAction = mock(MessageEditAction.class);
        when(fetched.editMessageEmbeds(any(MessageEmbed.class))).thenReturn(editAction);
        when(editAction.complete()).thenReturn(fetched);

        guildGuard = mock(GuildGuard.class);
        when(guildGuard.resolveGuildId(anyString())).thenAnswer(inv -> inv.getArgument(0));
        targetGuard = mock(TargetGuard.class);
    }

    private ApprovalService newService(Path tmp, EventStore[] storeOut) {
        EventStore eventStore = new EventStore(tmp.resolve("e.db").toString(), 14);
        storeOut[0] = eventStore;
        return new ApprovalService(jda, eventStore, guildGuard, targetGuard, CHANNEL_ID, APPROVER + "," + OTHER_APPROVER, 24);
    }

    private String statusOf(EventStore eventStore, long id) throws Exception {
        try (Connection c = eventStore.openConnection();
             PreparedStatement ps = c.prepareStatement("SELECT status FROM pending_actions WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString("status");
            }
        }
    }

    private long soleRowId(EventStore eventStore) throws Exception {
        try (Connection c = eventStore.openConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM pending_actions ORDER BY id DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getLong("id");
        }
    }

    @Test
    void requestApproval_storesPendingRow_returnsPlaceholder_postsEmbedWithReactions(@TempDir Path tmp) throws Exception {
        EventStore[] holder = new EventStore[1];
        ApprovalService service = newService(tmp, holder);

        String result = service.requestApproval("kick_member",
                "{\"guildId\":\"1000\",\"userId\":\"5\"}", "kick_member on 5", "spamming");

        assertTrue(result.matches("PENDING_APPROVAL #\\d+: waiting for a human\\. Do not retry\\."));
        verify(channel).sendMessageEmbeds(any(MessageEmbed.class));
        verify(posted, times(2)).addReaction(any(Emoji.class));
        assertEquals("pending", statusOf(holder[0], soleRowId(holder[0])));
    }

    @Test
    void requestApproval_missingConfig_throws(@TempDir Path tmp) {
        EventStore eventStore = new EventStore(tmp.resolve("e.db").toString(), 14);
        ApprovalService service = new ApprovalService(jda, eventStore, guildGuard, targetGuard, "", "", 24);

        assertThrows(IllegalStateException.class,
                () -> service.requestApproval("kick_member", "{}", "kick_member on 5", "reason"));
    }

    @Test
    void nonApproverReaction_ignored_delegateNeverCalled_statusStillPending(@TempDir Path tmp) throws Exception {
        EventStore[] holder = new EventStore[1];
        ApprovalService service = newService(tmp, holder);
        ToolCallback delegate = mock(ToolCallback.class);
        service.registerDelegate("kick_member", delegate);
        service.requestApproval("kick_member", "{\"guildId\":\"1000\",\"userId\":\"5\"}", "kick_member on 5", "spam");
        long id = soleRowId(holder[0]);

        service.onReaction(CHANNEL_ID, POSTED_MESSAGE_ID, NON_APPROVER, "✅");

        verify(delegate, never()).call(anyString());
        assertEquals("pending", statusOf(holder[0], id));
    }

    @Test
    void approverApproves_delegateCalledOnce_statusDone(@TempDir Path tmp) throws Exception {
        EventStore[] holder = new EventStore[1];
        ApprovalService service = newService(tmp, holder);
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.call(anyString())).thenReturn("kicked user 5");
        service.registerDelegate("kick_member", delegate);
        service.requestApproval("kick_member", "{\"guildId\":\"1000\",\"userId\":\"5\"}", "kick_member on 5", "spam");
        long id = soleRowId(holder[0]);

        service.onReaction(CHANNEL_ID, POSTED_MESSAGE_ID, APPROVER, "✅");

        verify(delegate, times(1)).call(anyString());
        assertEquals("done", statusOf(holder[0], id));
    }

    @Test
    void secondApproveReaction_doesNotExecuteTwice(@TempDir Path tmp) throws Exception {
        EventStore[] holder = new EventStore[1];
        ApprovalService service = newService(tmp, holder);
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.call(anyString())).thenReturn("kicked");
        service.registerDelegate("kick_member", delegate);
        service.requestApproval("kick_member", "{\"guildId\":\"1000\",\"userId\":\"5\"}", "kick_member on 5", "spam");

        service.onReaction(CHANNEL_ID, POSTED_MESSAGE_ID, APPROVER, "✅");
        service.onReaction(CHANNEL_ID, POSTED_MESSAGE_ID, OTHER_APPROVER, "✅");

        verify(delegate, times(1)).call(anyString());
    }

    @Test
    void approverRejects_statusRejected_delegateNeverCalled(@TempDir Path tmp) throws Exception {
        EventStore[] holder = new EventStore[1];
        ApprovalService service = newService(tmp, holder);
        ToolCallback delegate = mock(ToolCallback.class);
        service.registerDelegate("kick_member", delegate);
        service.requestApproval("kick_member", "{\"guildId\":\"1000\",\"userId\":\"5\"}", "kick_member on 5", "spam");
        long id = soleRowId(holder[0]);

        service.onReaction(CHANNEL_ID, POSTED_MESSAGE_ID, APPROVER, "❌");

        verify(delegate, never()).call(anyString());
        assertEquals("rejected", statusOf(holder[0], id));
    }

    @Test
    void guardFailureAtExecutionTime_statusFailed_delegateNeverCalled(@TempDir Path tmp) throws Exception {
        EventStore[] holder = new EventStore[1];
        ApprovalService service = newService(tmp, holder);
        ToolCallback delegate = mock(ToolCallback.class);
        service.registerDelegate("kick_member", delegate);
        service.requestApproval("kick_member", "{\"guildId\":\"1000\",\"userId\":\"5\"}", "kick_member on 5", "spam");
        long id = soleRowId(holder[0]);
        doThrow(new IllegalArgumentException("Cannot target the guild owner"))
                .when(targetGuard).checkToolCall(eq("kick_member"), anyMap());

        service.onReaction(CHANNEL_ID, POSTED_MESSAGE_ID, APPROVER, "✅");

        verify(delegate, never()).call(anyString());
        assertEquals("failed", statusOf(holder[0], id));
    }

    @Test
    void expirePending_marksOverdueRowsExpired(@TempDir Path tmp) throws Exception {
        EventStore eventStore = new EventStore(tmp.resolve("e.db").toString(), 14);
        ApprovalService service = new ApprovalService(jda, eventStore, guildGuard, targetGuard, CHANNEL_ID,
                APPROVER + "," + OTHER_APPROVER, 24);
        String past = Instant.now().minus(1, ChronoUnit.HOURS).toString();
        eventStore.submitWrite(() -> {
            try (Connection c = eventStore.openConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO pending_actions(requested_at, expires_at, tool, input_json, summary, approval_message_id, status) "
                                 + "VALUES(?,?,?,?,?,?, 'pending')")) {
                ps.setString(1, Instant.now().minus(2, ChronoUnit.HOURS).toString());
                ps.setString(2, past);
                ps.setString(3, "kick_member");
                ps.setString(4, "{}");
                ps.setString(5, "kick_member on 5");
                ps.setString(6, "12345");
                ps.executeUpdate();
                return null;
            }
        }).get();

        int expired = service.expirePending();

        assertEquals(1, expired);
        assertEquals("expired", statusOf(eventStore, 1));
    }
}
