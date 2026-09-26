package dev.saseq.configs;

import dev.saseq.guards.DestructiveMode;
import dev.saseq.guards.GuardedToolCallback;
import dev.saseq.guards.GuildGuard;
import dev.saseq.guards.TargetGuard;
import dev.saseq.guards.ToolClassification;
import dev.saseq.services.DiscordService;
import dev.saseq.services.MessageService;
import dev.saseq.services.UserService;
import dev.saseq.services.ChannelService;
import dev.saseq.services.CategoryService;
import dev.saseq.services.WebhookService;
import dev.saseq.services.ThreadService;
import dev.saseq.services.ModerationService;
import dev.saseq.services.RoleService;
import dev.saseq.services.VoiceChannelService;
import dev.saseq.services.ScheduledEventService;
import dev.saseq.services.InviteService;
import dev.saseq.services.ChannelPermissionService;
import dev.saseq.services.EmojiService;
import dev.saseq.services.ForumService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

@Configuration
public class DiscordMcpConfig {
    private static final Logger log = LoggerFactory.getLogger(DiscordMcpConfig.class);

    @Bean
    public List<ToolCallback> guardedDiscordToolCallbacks(DiscordService discordService,
                                             MessageService messageService,
                                             UserService userService,
                                             ChannelService channelService,
                                             CategoryService categoryService,
                                             WebhookService webhookService,
                                             ThreadService threadService,
                                             RoleService roleService,
                                             ModerationService moderationService,
                                             VoiceChannelService voiceChannelService,
                                             ScheduledEventService scheduledEventService,
                                             InviteService inviteService,
                                             ChannelPermissionService channelPermissionService,
                                             EmojiService emojiService,
                                             ForumService forumService,
                                             GuildGuard guildGuard,
                                             TargetGuard targetGuard,
                                             @Value("${DESTRUCTIVE_MODE:dry_run}") String destructiveModeValue,
                                             @Value("${ENABLE_DM_TOOLS:true}") boolean enableDmTools) {
        DestructiveMode destructiveMode = DestructiveMode.parse(destructiveModeValue);
        ToolCallback[] rawCallbacks = MethodToolCallbackProvider.builder().toolObjects(
                discordService,
                messageService,
                userService,
                channelService,
                categoryService,
                webhookService,
                threadService,
                roleService,
                moderationService,
                voiceChannelService,
                scheduledEventService,
                inviteService,
                channelPermissionService,
                emojiService,
                forumService
        ).build().getToolCallbacks();

        List<ToolCallback> filtered = filterDmTools(Arrays.asList(rawCallbacks), enableDmTools);
        log.info("Registering {} MCP tools (dmToolsEnabled={}, destructiveMode={})", filtered.size(), enableDmTools, destructiveMode);
        return filtered.stream()
                .<ToolCallback>map(callback -> new GuardedToolCallback(callback, guildGuard, targetGuard, destructiveMode))
                .toList();
    }

    /** Package-visible so it can be unit tested without a Spring context. */
    static List<ToolCallback> filterDmTools(List<ToolCallback> callbacks, boolean enableDmTools) {
        if (enableDmTools) {
            return callbacks;
        }
        return callbacks.stream()
                .filter(callback -> !GuardedToolCallback.DM_TOOLS.contains(callback.getToolDefinition().name()))
                .toList();
    }

    @Bean
    public List<McpServerFeatures.SyncToolSpecification> guardedToolSpecifications(List<ToolCallback> guardedDiscordToolCallbacks) {
        return McpToolUtils.toSyncToolSpecification(guardedDiscordToolCallbacks).stream()
                .map(DiscordMcpConfig::withAnnotations)
                .toList();
    }

    /** Package-visible so the annotation mapping can be unit tested without a Spring context. */
    static McpServerFeatures.SyncToolSpecification withAnnotations(McpServerFeatures.SyncToolSpecification spec) {
        McpSchema.Tool tool = spec.tool();
        McpSchema.ToolAnnotations annotations = buildAnnotations(tool.name(), tool.title());
        McpSchema.Tool updated = McpSchema.Tool.builder()
                .name(tool.name())
                .title(tool.title())
                .description(tool.description())
                .inputSchema(tool.inputSchema())
                .outputSchema(tool.outputSchema())
                .annotations(annotations)
                .meta(tool.meta())
                .build();
        return new McpServerFeatures.SyncToolSpecification(updated, spec.callHandler());
    }

    static McpSchema.ToolAnnotations buildAnnotations(String toolName, String title) {
        boolean readOnly = ToolClassification.of(toolName)
                .filter(category -> category == ToolClassification.Category.READ_ONLY)
                .isPresent();
        boolean destructive = ToolClassification.of(toolName)
                .filter(category -> category == ToolClassification.Category.DESTRUCTIVE)
                .isPresent();
        return new McpSchema.ToolAnnotations(title, readOnly, destructive, false, true, false);
    }

    @Bean
    public JDA jda(@Value("${DISCORD_TOKEN:}") String token) throws InterruptedException {
        if (token == null || token.isBlank()) {
            log.error("The environment variable DISCORD_TOKEN is not set. Please set it to run the application properly.");
            throw new IllegalStateException("DISCORD_TOKEN is not set");
        }
        return JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.SCHEDULED_EVENTS)
                .build()
                .awaitReady();
    }
}
