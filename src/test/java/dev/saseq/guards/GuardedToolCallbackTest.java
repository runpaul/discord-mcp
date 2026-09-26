package dev.saseq.guards;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuardedToolCallbackTest {

    private static final String GUILD_ID = "1000";

    private static final String SCHEMA_WITH_TARGET_AND_REASON = """
            {"type":"object","properties":{
              "guildId":{"type":"string"},
              "channelId":{"type":"string"},
              "userId":{"type":"string"},
              "reason":{"type":"string"}
            }}""";

    private static final String SCHEMA_NO_TARGET = """
            {"type":"object","properties":{
              "text":{"type":"string"}
            }}""";

    private GuildGuard guildGuard;
    private TargetGuard targetGuard;

    @BeforeEach
    void setUp() {
        guildGuard = mock(GuildGuard.class);
        targetGuard = mock(TargetGuard.class);
        when(guildGuard.resolveGuildId(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(guildGuard.resolveGuildId(eq(null))).thenReturn(GUILD_ID);
    }

    private ToolCallback mockDelegate(String name, String description, String schema) {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(schema)
                .build();
        when(delegate.getToolDefinition()).thenReturn(definition);
        return delegate;
    }

    @Test
    void destructiveTool_dryRunMode_neverCallsDelegate_andReturnsDryRunMessage() {
        ToolCallback delegate = mockDelegate("delete_message", "Deletes a message", SCHEMA_WITH_TARGET_AND_REASON);
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guildGuard, targetGuard, DestructiveMode.DRY_RUN);

        String result = guarded.call("{\"guildId\":\"1000\",\"channelId\":\"5\",\"messageId\":\"9\"}");

        assertTrue(result.startsWith("[DRY RUN] Would delete_message on"));
        verify(delegate, never()).call(anyString());
        verify(delegate, never()).call(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void destructiveTool_denyMode_throws_delegateUntouched() {
        ToolCallback delegate = mockDelegate("delete_message", "Deletes a message", SCHEMA_WITH_TARGET_AND_REASON);
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guildGuard, targetGuard, DestructiveMode.DENY);

        assertThrows(IllegalStateException.class,
                () -> guarded.call("{\"guildId\":\"1000\",\"channelId\":\"5\",\"messageId\":\"9\"}"));
        verify(delegate, never()).call(anyString());
    }

    @Test
    void destructiveTool_allowMode_delegates() {
        ToolCallback delegate = mockDelegate("delete_message", "Deletes a message", SCHEMA_WITH_TARGET_AND_REASON);
        when(delegate.call(anyString())).thenReturn("deleted");
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guildGuard, targetGuard, DestructiveMode.ALLOW);

        String result = guarded.call("{\"guildId\":\"1000\",\"channelId\":\"5\",\"messageId\":\"9\"}");

        assertEquals("deleted", result);
        verify(delegate).call(anyString());
    }

    @Test
    void destructiveTool_approvalMode_throwsNotImplemented() {
        ToolCallback delegate = mockDelegate("delete_message", "Deletes a message", SCHEMA_WITH_TARGET_AND_REASON);
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guildGuard, targetGuard, DestructiveMode.APPROVAL);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guarded.call("{\"guildId\":\"1000\",\"channelId\":\"5\",\"messageId\":\"9\"}"));
        assertEquals("approval mode not implemented yet", ex.getMessage());
        verify(delegate, never()).call(anyString());
    }

    @Test
    void nonDestructiveTool_passesThrough() {
        ToolCallback delegate = mockDelegate("send_message", "Sends a message", SCHEMA_WITH_TARGET_AND_REASON);
        when(delegate.call(anyString())).thenReturn("sent");
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guildGuard, targetGuard, DestructiveMode.DENY);

        String result = guarded.call("{\"guildId\":\"1000\",\"channelId\":\"5\"}");

        assertEquals("sent", result);
        verify(delegate).call(anyString());
    }

    @Test
    void guildNotAllowlisted_isRejected() {
        ToolCallback delegate = mockDelegate("send_message", "Sends a message", SCHEMA_WITH_TARGET_AND_REASON);
        doThrow(new IllegalArgumentException("Guild 1000 is not allowlisted")).when(guildGuard).checkGuild("1000");
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guildGuard, targetGuard, DestructiveMode.ALLOW);

        assertThrows(IllegalArgumentException.class, () -> guarded.call("{\"guildId\":\"1000\",\"channelId\":\"5\"}"));
        verify(delegate, never()).call(anyString());
    }

    @Test
    void channelInNonAllowlistedGuild_isRejected() {
        ToolCallback delegate = mockDelegate("send_message", "Sends a message", SCHEMA_WITH_TARGET_AND_REASON);
        doThrow(new IllegalArgumentException("Channel 5 belongs to a guild that is not allowlisted"))
                .when(guildGuard).checkChannel("5");
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guildGuard, targetGuard, DestructiveMode.ALLOW);

        assertThrows(IllegalArgumentException.class, () -> guarded.call("{\"guildId\":\"1000\",\"channelId\":\"5\"}"));
        verify(delegate, never()).call(anyString());
    }

    @Test
    void targetGuardRefusal_propagatesBeforeDryRun_delegateUntouched() {
        ToolCallback delegate = mockDelegate("kick_member", "Kicks a member", SCHEMA_WITH_TARGET_AND_REASON);
        doThrow(new IllegalArgumentException("Cannot target the guild owner"))
                .when(targetGuard).checkToolCall(eq("kick_member"), anyMap());
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guildGuard, targetGuard, DestructiveMode.DRY_RUN);

        assertThrows(IllegalArgumentException.class,
                () -> guarded.call("{\"guildId\":\"1000\",\"userId\":\"2\"}"));
        verify(delegate, never()).call(anyString());
    }

    @Test
    void reasonInjected_whenBlank() {
        ToolCallback delegate = mockDelegate("send_message", "Sends a message", SCHEMA_WITH_TARGET_AND_REASON);
        when(delegate.call(anyString())).thenReturn("ok");
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guildGuard, targetGuard, DestructiveMode.ALLOW);

        guarded.call("{\"guildId\":\"1000\",\"channelId\":\"5\"}");

        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(delegate).call(captor.capture());
        assertTrue(captor.getValue().contains("via discord-mcp (agent)"));
    }

    @Test
    void reasonNotInjected_whenSchemaHasNoReasonProperty() {
        ToolCallback delegate = mockDelegate("read_messages", "Reads messages", SCHEMA_NO_TARGET);
        when(delegate.call(anyString())).thenReturn("ok");
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guildGuard, targetGuard, DestructiveMode.ALLOW);

        String input = "{\"text\":\"hi\"}";
        guarded.call(input);

        verify(delegate).call(eq(input));
    }

    @Test
    void descriptionIsPrefixed_forDestructiveTools() {
        ToolCallback delegate = mockDelegate("delete_message", "Deletes a message", SCHEMA_WITH_TARGET_AND_REASON);
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guildGuard, targetGuard, DestructiveMode.DRY_RUN);

        assertEquals("[DESTRUCTIVE] Deletes a message", guarded.getToolDefinition().description());
    }

    @Test
    void descriptionIsNotPrefixed_forNonDestructiveTools() {
        ToolCallback delegate = mockDelegate("send_message", "Sends a message", SCHEMA_WITH_TARGET_AND_REASON);
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guildGuard, targetGuard, DestructiveMode.DRY_RUN);

        assertEquals("Sends a message", guarded.getToolDefinition().description());
    }

    @Test
    void dmTool_skipsGuildChecks() {
        ToolCallback delegate = mockDelegate("send_private_message", "Sends a DM", SCHEMA_WITH_TARGET_AND_REASON);
        when(delegate.call(anyString())).thenReturn("sent");
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guildGuard, targetGuard, DestructiveMode.ALLOW);

        guarded.call("{\"userId\":\"2\"}");

        verify(guildGuard, never()).checkGuild(anyString());
    }
}
