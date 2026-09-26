package dev.saseq.guards;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.util.json.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Delegating {@link ToolCallback} applied at registration to every MCP tool. Enforces, in order:
 * guild allowlisting (G4), target safety (G6), reason injection (G7) and destructive-mode
 * handling, then emits a single audit log line for every mutating call (G7 audit).
 */
public class GuardedToolCallback implements ToolCallback {

	private static final Logger log = LoggerFactory.getLogger(GuardedToolCallback.class);

	public static final Set<String> DM_TOOLS = Set.of(
			"send_private_message", "edit_private_message", "delete_private_message", "read_private_messages"
	);

	private static final String DEFAULT_REASON = "via discord-mcp (agent)";

	/** Thrown when a destructive tool call is refused by the configured destructive mode (deny/approval). */
	static final class DeniedException extends IllegalStateException {
		DeniedException(String message) {
			super(message);
		}
	}

	private final ToolCallback delegate;
	private final GuildGuard guildGuard;
	private final TargetGuard targetGuard;
	private final DestructiveMode destructiveMode;

	private final String toolName;
	private final boolean destructive;
	private final boolean dmTool;
	private final boolean guildScoped;
	private final boolean hasReasonProperty;
	private final boolean mutating;

	public GuardedToolCallback(ToolCallback delegate, GuildGuard guildGuard, TargetGuard targetGuard, DestructiveMode destructiveMode) {
		this.delegate = delegate;
		this.guildGuard = guildGuard;
		this.targetGuard = targetGuard;
		this.destructiveMode = destructiveMode;

		ToolDefinition definition = delegate.getToolDefinition();
		this.toolName = definition.name();
		this.destructive = ToolClassification.of(toolName)
				.filter(c -> c == ToolClassification.Category.DESTRUCTIVE)
				.isPresent();
		this.dmTool = DM_TOOLS.contains(toolName);
		this.mutating = ToolClassification.isMutating(toolName);

		Map<String, Object> schemaProperties = readSchemaProperties(definition.inputSchema());
		this.guildScoped = schemaProperties.containsKey("guildId")
				|| schemaProperties.containsKey("channelId")
				|| schemaProperties.containsKey("userId")
				|| schemaProperties.containsKey("roleId")
				|| schemaProperties.containsKey("messageId");
		this.hasReasonProperty = schemaProperties.containsKey("reason");
	}

	@Override
	public ToolDefinition getToolDefinition() {
		ToolDefinition original = delegate.getToolDefinition();
		String description = destructive ? "[DESTRUCTIVE] " + original.description() : original.description();
		return ToolDefinition.builder()
				.name(original.name())
				.description(description)
				.inputSchema(original.inputSchema())
				.build();
	}

	@Override
	public ToolMetadata getToolMetadata() {
		return delegate.getToolMetadata();
	}

	@Override
	public String call(String input) {
		return call(input, null);
	}

	@Override
	public String call(String input, ToolContext toolContext) {
		Map<String, Object> args = parseArgs(input);
		String guildId = null;
		String targetId = null;
		String reason = null;
		String outcome = "error";
		try {
			guildId = guildGuard.resolveGuildId(asString(args.get("guildId")));
			runGuildChecks(args, guildId);

			targetGuard.checkToolCall(toolName, args);

			String effectiveInput = applyReasonInjection(input, args);
			reason = asString(args.get("reason"));
			targetId = firstNonBlank(asString(args.get("userId")), asString(args.get("roleId")),
					asString(args.get("messageId")), asString(args.get("channelId")), guildId);

			if (destructive) {
				String result = applyDestructiveMode(effectiveInput, toolContext, targetId, reason);
				outcome = destructiveMode == DestructiveMode.ALLOW ? "ok" : "dry_run";
				return result;
			}

			String result = toolContext == null ? delegate.call(effectiveInput) : delegate.call(effectiveInput, toolContext);
			outcome = "ok";
			return result;
		} catch (DeniedException denied) {
			outcome = "denied";
			throw denied;
		} catch (IllegalArgumentException | IllegalStateException refusal) {
			outcome = "refused";
			throw refusal;
		} catch (RuntimeException e) {
			outcome = "error";
			throw e;
		} finally {
			if (mutating) {
				audit(guildId, targetId, reason, outcome);
			}
		}
	}

	private void runGuildChecks(Map<String, Object> args, String guildId) {
		if (dmTool) {
			return;
		}
		if (guildScoped) {
			guildGuard.checkGuild(guildId);
		}
		String channelId = asString(args.get("channelId"));
		if (channelId != null && !channelId.isBlank()) {
			guildGuard.checkChannel(channelId);
		}
	}

	private String applyReasonInjection(String originalInput, Map<String, Object> args) {
		if (!hasReasonProperty) {
			return originalInput;
		}
		String existing = asString(args.get("reason"));
		if (existing != null && !existing.isBlank()) {
			return originalInput;
		}
		args.put("reason", DEFAULT_REASON);
		return JsonParser.toJson(args);
	}

	private String applyDestructiveMode(String effectiveInput, ToolContext toolContext, String targetId, String reason) {
		return switch (destructiveMode) {
			case ALLOW -> toolContext == null ? delegate.call(effectiveInput) : delegate.call(effectiveInput, toolContext);
			case DRY_RUN -> "[DRY RUN] Would " + toolName + " on " + targetId
					+ " (reason: " + (reason == null || reason.isBlank() ? "none" : reason) + ")";
			case DENY -> throw new DeniedException("Destructive tool " + toolName + " is disabled (DESTRUCTIVE_MODE=deny)");
			case APPROVAL -> handleApprovalMode();
		};
	}

	// Single, clearly-marked seam: Phase 3 replaces this method's body with the real
	// approval workflow. For now every destructive call under APPROVAL mode is refused.
	private String handleApprovalMode() {
		throw new DeniedException("approval mode not implemented yet");
	}

	private void audit(String guildId, String targetId, String reason, String outcome) {
		log.info("tool={} guildId={} targetId={} reason={} mode={} outcome={}",
				toolName, guildId, targetId, reason, destructiveMode, outcome);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parseArgs(String input) {
		if (input == null || input.isBlank()) {
			return new LinkedHashMap<>();
		}
		Map<String, Object> parsed = JsonParser.fromJson(input, Map.class);
		return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> readSchemaProperties(String schemaJson) {
		if (schemaJson == null || schemaJson.isBlank()) {
			return Map.of();
		}
		Map<String, Object> schema = JsonParser.fromJson(schemaJson, Map.class);
		if (schema == null) {
			return Map.of();
		}
		Object properties = schema.get("properties");
		if (properties instanceof Map<?, ?> map) {
			return (Map<String, Object>) map;
		}
		return Map.of();
	}

	private static String asString(Object value) {
		return value == null ? null : value.toString();
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}
}
