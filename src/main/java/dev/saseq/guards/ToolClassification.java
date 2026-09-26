package dev.saseq.guards;

import java.util.Optional;
import java.util.Set;

public final class ToolClassification {

	private ToolClassification() {
	}

	public enum Category {
		DESTRUCTIVE, READ_ONLY, WRITE_NON_DESTRUCTIVE
	}

	public static final Set<String> DESTRUCTIVE = Set.of(
			"kick_member",
			"ban_member",
			"timeout_member",
			"remove_role",
			"disconnect_member",
			"modify_voice_state",
			"delete_category",
			"delete_channel",
			"delete_channel_permission_overwrite",
			"delete_emoji",
			"delete_guild_scheduled_event",
			"delete_invite",
			"delete_message",
			"delete_private_message",
			"delete_role",
			"delete_webhook"
	);

	public static final Set<String> READ_ONLY = Set.of(
			"get_attachment",
			"get_bans",
			"get_channel_info",
			"get_emoji_details",
			"get_forum_channel_info",
			"get_guild_scheduled_event_users",
			"get_invite_details",
			"get_server_info",
			"get_user_id_by_name",
			"find_category",
			"find_channel",
			"list_active_threads",
			"list_channel_permission_overwrites",
			"list_channels",
			"list_channels_in_category",
			"list_emojis",
			"list_forum_channels",
			"list_forum_posts",
			"list_forum_tags",
			"list_guild_scheduled_events",
			"list_invites",
			"list_roles",
			"list_webhooks",
			"read_messages",
			"read_private_messages"
	);

	public static final Set<String> WRITE_NON_DESTRUCTIVE = Set.of(
			"add_reaction",
			"assign_role",
			"create_category",
			"create_emoji",
			"create_forum_channel",
			"create_forum_post",
			"create_guild_scheduled_event",
			"create_invite",
			"create_role",
			"create_stage_channel",
			"create_text_channel",
			"create_voice_channel",
			"create_webhook",
			"edit_category",
			"edit_emoji",
			"edit_forum_channel",
			"edit_guild_scheduled_event",
			"edit_message",
			"edit_private_message",
			"edit_role",
			"edit_text_channel",
			"edit_voice_channel",
			"modify_forum_post",
			"move_channel",
			"move_member",
			"remove_reaction",
			"remove_timeout",
			"send_message",
			"send_private_message",
			"send_webhook_message",
			"set_nickname",
			"unban_member",
			"upsert_member_channel_permissions",
			"upsert_role_channel_permissions"
	);

	public static Optional<Category> of(String toolName) {
		if (DESTRUCTIVE.contains(toolName)) {
			return Optional.of(Category.DESTRUCTIVE);
		}
		if (READ_ONLY.contains(toolName)) {
			return Optional.of(Category.READ_ONLY);
		}
		if (WRITE_NON_DESTRUCTIVE.contains(toolName)) {
			return Optional.of(Category.WRITE_NON_DESTRUCTIVE);
		}
		return Optional.empty();
	}

	public static boolean isMutating(String toolName) {
		return DESTRUCTIVE.contains(toolName) || WRITE_NON_DESTRUCTIVE.contains(toolName);
	}

}
