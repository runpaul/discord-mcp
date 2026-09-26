package dev.saseq.events;

import dev.saseq.guards.GuildGuard;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Mentions;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.SelfUser;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordEventListenerTest {

    private static final String GUILD_ID = "1000";

    private EventStore eventStore;
    private GuildGuard guildGuard;
    private DiscordEventListener listener;

    @BeforeEach
    void setUp() {
        eventStore = mock(EventStore.class);
        guildGuard = mock(GuildGuard.class);
        when(guildGuard.isAllowed(GUILD_ID)).thenReturn(true);
        listener = new DiscordEventListener(eventStore, guildGuard);
    }

    private MessageReceivedEvent messageEvent(boolean fromGuild, boolean isBot, boolean mentionsBot, String content) {
        MessageReceivedEvent event = mock(MessageReceivedEvent.class);
        Guild guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD_ID);
        User author = mock(User.class);
        when(author.isBot()).thenReturn(isBot);
        when(author.getId()).thenReturn("u1");
        when(author.getName()).thenReturn("alice");
        Message message = mock(Message.class);
        Mentions mentions = mock(Mentions.class);
        when(mentions.isMentioned(org.mockito.ArgumentMatchers.any())).thenReturn(mentionsBot);
        when(message.getMentions()).thenReturn(mentions);
        when(message.getContentDisplay()).thenReturn(content);
        when(message.getAttachments()).thenReturn(List.of());
        JDA jda = mock(JDA.class);
        SelfUser selfUser = mock(SelfUser.class);
        when(jda.getSelfUser()).thenReturn(selfUser);
        MessageChannelUnion channel = mock(MessageChannelUnion.class);
        when(channel.getId()).thenReturn("c1");

        when(event.isFromGuild()).thenReturn(fromGuild);
        when(event.getGuild()).thenReturn(guild);
        when(event.getAuthor()).thenReturn(author);
        when(event.getMessage()).thenReturn(message);
        when(event.getJDA()).thenReturn(jda);
        when(event.getChannel()).thenReturn(channel);
        return event;
    }

    @Test
    void humanMessage_isRecordedWithBoundedPreview() {
        String longContent = "x".repeat(400);
        MessageReceivedEvent event = messageEvent(true, false, false, longContent);

        listener.onMessageReceived(event);

        verify(eventStore).record(argThat(r ->
                "message".equals(r.type())
                        && "u1".equals(r.userId())
                        && ((String) r.payload().get("content_preview")).length() <= 300));
    }

    @Test
    void botMessageWithoutMention_isIgnored() {
        MessageReceivedEvent event = messageEvent(true, true, false, "hello");

        listener.onMessageReceived(event);

        verify(eventStore, never()).record(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void botMessageMentioningOurBot_isRecordedIncludingBotMention() {
        MessageReceivedEvent event = messageEvent(true, true, true, "hi @bot");

        listener.onMessageReceived(event);

        verify(eventStore).record(argThat(r -> "message".equals(r.type())));
        verify(eventStore).record(argThat(r -> "bot_mention".equals(r.type())));
    }

    @Test
    void messageInNonAllowlistedGuild_isIgnored() {
        when(guildGuard.isAllowed(GUILD_ID)).thenReturn(false);
        MessageReceivedEvent event = messageEvent(true, false, false, "hello");

        listener.onMessageReceived(event);

        verify(eventStore, never()).record(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void voiceMove_isRecordedWithFromAndTo() {
        GuildVoiceUpdateEvent event = mock(GuildVoiceUpdateEvent.class);
        Guild guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD_ID);
        Member member = mock(Member.class);
        when(member.getId()).thenReturn("u1");
        User user = mock(User.class);
        when(user.getName()).thenReturn("alice");
        when(member.getUser()).thenReturn(user);
        AudioChannelUnion from = mock(AudioChannelUnion.class);
        when(from.getId()).thenReturn("c_old");
        when(from.getName()).thenReturn("Lounge");
        AudioChannelUnion to = mock(AudioChannelUnion.class);
        when(to.getId()).thenReturn("c_new");
        when(to.getName()).thenReturn("General");

        when(event.getGuild()).thenReturn(guild);
        when(event.getMember()).thenReturn(member);
        when(event.getChannelLeft()).thenReturn(from);
        when(event.getChannelJoined()).thenReturn(to);

        listener.onGuildVoiceUpdate(event);

        verify(eventStore).record(argThat(r ->
                "voice_move".equals(r.type())
                        && "u1".equals(r.userId())
                        && r.payload().get("from") != null
                        && r.payload().get("to") != null));
    }
}
