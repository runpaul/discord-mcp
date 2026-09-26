package dev.saseq.events;

import dev.saseq.guards.ApprovalService;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bridges JDA reaction-add events to {@link ApprovalService#onReaction}. Registration with
 * JDA happens in the orchestrator's config, not here (mirrors {@link DiscordEventListener}).
 */
@Component
public class ApprovalReactionListener extends ListenerAdapter {

	private static final Logger log = LoggerFactory.getLogger(ApprovalReactionListener.class);

	private final ApprovalService approvalService;

	public ApprovalReactionListener(ApprovalService approvalService) {
		this.approvalService = approvalService;
	}

	@Override
	public void onMessageReactionAdd(MessageReactionAddEvent event) {
		if (!event.isFromGuild()) {
			return;
		}
		try {
			String channelId = event.getChannel().getId();
			String messageId = event.getMessageId();
			String userId = String.valueOf(event.getUserIdLong());
			Emoji emoji = event.getEmoji();
			String emojiName = emoji == null ? null : emoji.getName();
			approvalService.onReaction(channelId, messageId, userId, emojiName);
		} catch (RuntimeException e) {
			log.warn("Failed to process approval reaction: {}", e.getMessage());
		}
	}
}
