package dev.saseq.events;

import dev.saseq.guards.ApprovalService;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Bridges JDA reaction-add events to {@link ApprovalService#onReaction}. Registration with
 * JDA happens in the orchestrator's config, not here (mirrors {@link DiscordEventListener}).
 */
@Component
public class ApprovalReactionListener extends ListenerAdapter {

	private static final Logger log = LoggerFactory.getLogger(ApprovalReactionListener.class);

	private final ApprovalService approvalService;
	private final Executor executor;

	@Autowired
	public ApprovalReactionListener(ApprovalService approvalService) {
		// Approved actions call Discord with blocking complete(); keep that off JDA's event thread.
		this(approvalService, Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "approval-executor");
			t.setDaemon(true);
			return t;
		}));
	}

	ApprovalReactionListener(ApprovalService approvalService, Executor executor) {
		this.executor = executor;
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
			executor.execute(() -> {
				try {
					approvalService.onReaction(channelId, messageId, userId, emojiName);
				} catch (RuntimeException e) {
					log.warn("Failed to process approval reaction: {}", e.getMessage());
				}
			});
		} catch (RuntimeException e) {
			log.warn("Failed to process approval reaction: {}", e.getMessage());
		}
	}
}
