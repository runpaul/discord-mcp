package dev.saseq.events;

import dev.saseq.guards.ApprovalService;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.entities.emoji.EmojiUnion;
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalReactionListenerTest {

    @Test
    void guildReaction_delegatesToApprovalService() {
        ApprovalService approvalService = mock(ApprovalService.class);
        ApprovalReactionListener listener = new ApprovalReactionListener(approvalService, Runnable::run);

        MessageReactionAddEvent event = mock(MessageReactionAddEvent.class);
        when(event.isFromGuild()).thenReturn(true);
        MessageChannelUnion channel = mock(MessageChannelUnion.class);
        when(channel.getId()).thenReturn("700");
        when(event.getChannel()).thenReturn(channel);
        when(event.getMessageId()).thenReturn("999");
        when(event.getUserIdLong()).thenReturn(42L);
        EmojiUnion emoji = mock(EmojiUnion.class);
        when(emoji.getName()).thenReturn("✅");
        when(event.getEmoji()).thenReturn(emoji);

        listener.onMessageReactionAdd(event);

        verify(approvalService).onReaction(eq("700"), eq("999"), eq("42"), eq("✅"));
    }

    @Test
    void nonGuildReaction_ignored() {
        ApprovalService approvalService = mock(ApprovalService.class);
        ApprovalReactionListener listener = new ApprovalReactionListener(approvalService, Runnable::run);

        MessageReactionAddEvent event = mock(MessageReactionAddEvent.class);
        when(event.isFromGuild()).thenReturn(false);

        listener.onMessageReactionAdd(event);

        verify(approvalService, never()).onReaction(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void approvalServiceThrows_doesNotPropagate() {
        ApprovalService approvalService = mock(ApprovalService.class);
        ApprovalReactionListener listener = new ApprovalReactionListener(approvalService, Runnable::run);

        MessageReactionAddEvent event = mock(MessageReactionAddEvent.class);
        when(event.isFromGuild()).thenReturn(true);
        MessageChannelUnion channel = mock(MessageChannelUnion.class);
        when(channel.getId()).thenReturn("700");
        when(event.getChannel()).thenReturn(channel);
        when(event.getMessageId()).thenReturn("999");
        when(event.getUserIdLong()).thenReturn(42L);
        EmojiUnion emoji = mock(EmojiUnion.class);
        when(emoji.getName()).thenReturn("✅");
        when(event.getEmoji()).thenReturn(emoji);
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(approvalService).onReaction(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());

        listener.onMessageReactionAdd(event);
    }
}
