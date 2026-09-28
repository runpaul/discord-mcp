package dev.saseq.configs;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscordMcpConfigTest {

    private ToolCallback mockCallback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description("desc")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    @Test
    void filterDmTools_keepsDmTools_whenEnabled() {
        List<ToolCallback> callbacks = List.of(
                mockCallback("send_private_message"),
                mockCallback("send_message")
        );

        List<ToolCallback> result = DiscordMcpConfig.filterDmTools(callbacks, true);

        assertEquals(2, result.size());
    }

    @Test
    void filterDmTools_dropsDmTools_whenDisabled() {
        List<ToolCallback> callbacks = List.of(
                mockCallback("send_private_message"),
                mockCallback("edit_private_message"),
                mockCallback("delete_private_message"),
                mockCallback("read_private_messages"),
                mockCallback("send_message")
        );

        List<ToolCallback> result = DiscordMcpConfig.filterDmTools(callbacks, false);

        assertEquals(1, result.size());
        assertEquals("send_message", result.get(0).getToolDefinition().name());
    }

    @Test
    void buildAnnotations_readOnlyTool_hasReadOnlyHint() {
        McpSchema.ToolAnnotations annotations = DiscordMcpConfig.buildAnnotations("list_channels", "list_channels");

        assertTrue(annotations.readOnlyHint());
        assertFalse(annotations.destructiveHint());
    }

    @Test
    void buildAnnotations_destructiveTool_hasDestructiveHint() {
        McpSchema.ToolAnnotations annotations = DiscordMcpConfig.buildAnnotations("delete_message", "delete_message");

        assertFalse(annotations.readOnlyHint());
        assertTrue(annotations.destructiveHint());
    }

    @Test
    void buildAnnotations_writeNonDestructiveTool_hasNeitherHint() {
        McpSchema.ToolAnnotations annotations = DiscordMcpConfig.buildAnnotations("send_message", "send_message");

        assertFalse(annotations.readOnlyHint());
        assertFalse(annotations.destructiveHint());
    }

    @Test
    void withAnnotations_preservesToolIdentity_andAddsAnnotations() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("delete_message")
                .description("[DESTRUCTIVE] Deletes a message")
                .inputSchema(new McpSchema.JsonSchema("object", null, null, null, null, null))
                .build();
        McpServerFeatures.SyncToolSpecification original = new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> null);

        McpServerFeatures.SyncToolSpecification updated = DiscordMcpConfig.withAnnotations(original);

        assertEquals("delete_message", updated.tool().name());
        assertEquals("[DESTRUCTIVE] Deletes a message", updated.tool().description());
        assertTrue(updated.tool().annotations().destructiveHint());
        assertEquals(original.callHandler(), updated.callHandler());
    }
}
