package dev.saseq.events;

import dev.saseq.guards.GuildGuard;
import net.dv8tion.jda.api.audit.ActionType;
import net.dv8tion.jda.api.audit.AuditLogEntry;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.channel.ChannelCreateEvent;
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent;
import net.dv8tion.jda.api.events.guild.GuildBanEvent;
import net.dv8tion.jda.api.events.guild.GuildUnbanEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateTimeOutEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.user.UserActivityEndEvent;
import net.dv8tion.jda.api.events.user.UserActivityStartEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns JDA gateway events into {@link EventRecord}s for the {@link EventStore}. Only events
 * from allowlisted guilds are recorded; DMs are always ignored. Registration with JDA happens
 * in the orchestrator's config, not here.
 */
@Component
public class DiscordEventListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(DiscordEventListener.class);
    private static final int CONTENT_PREVIEW_MAX = 300;

    private final EventStore eventStore;
    private final GuildGuard guildGuard;
    private final ActivityDebouncer activityDebouncer;

    public DiscordEventListener(EventStore eventStore, GuildGuard guildGuard) {
        this.eventStore = eventStore;
        this.guildGuard = guildGuard;
        this.activityDebouncer = new ActivityDebouncer(eventStore);
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        String guildId = event.getGuild().getId();
        if (!guildGuard.isAllowed(guildId)) {
            return;
        }
        User user = event.getUser();
        eventStore.record(new EventRecord("member_join", guildId, null, user.getId(), user.getName(), null));
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        String guildId = event.getGuild().getId();
        if (!guildGuard.isAllowed(guildId)) {
            return;
        }
        User user = event.getUser();
        eventStore.record(new EventRecord("member_leave", guildId, null, user.getId(), user.getName(), null));
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        String guildId = event.getGuild().getId();
        if (!guildGuard.isAllowed(guildId)) {
            return;
        }
        AudioChannelUnion left = event.getChannelLeft();
        AudioChannelUnion joined = event.getChannelJoined();
        String type = left != null && joined != null ? "voice_move" : joined != null ? "voice_join" : "voice_leave";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", channelInfo(left));
        payload.put("to", channelInfo(joined));
        Member member = event.getMember();
        eventStore.record(new EventRecord(type, guildId, null, member.getId(), member.getUser().getName(), payload));
    }

    @Override
    public void onUserActivityStart(UserActivityStartEvent event) {
        String guildId = event.getGuild().getId();
        if (!guildGuard.isAllowed(guildId)) {
            return;
        }
        Activity activity = event.getNewActivity();
        String activityType = activityTypeName(activity);
        if (activityType == null) {
            return;
        }
        Member member = event.getMember();
        activityDebouncer.onActivityStart(guildId, null, member.getId(), member.getUser().getName(),
                activityType, activity.getName());
    }

    @Override
    public void onUserActivityEnd(UserActivityEndEvent event) {
        String guildId = event.getGuild().getId();
        if (!guildGuard.isAllowed(guildId)) {
            return;
        }
        Activity activity = event.getOldActivity();
        String activityType = activityTypeName(activity);
        if (activityType == null) {
            return;
        }
        Member member = event.getMember();
        activityDebouncer.onActivityEnd(guildId, null, member.getId(), member.getUser().getName(),
                activityType, activity.getName());
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild()) {
            return;
        }
        String guildId = event.getGuild().getId();
        if (!guildGuard.isAllowed(guildId)) {
            return;
        }
        User author = event.getAuthor();
        boolean mentionsBot = event.getMessage().getMentions().isMentioned(event.getJDA().getSelfUser());
        if (author.isBot() && !mentionsBot) {
            return;
        }
        String content = event.getMessage().getContentDisplay();
        String preview = content.length() > CONTENT_PREVIEW_MAX ? content.substring(0, CONTENT_PREVIEW_MAX) : content;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("len", content.length());
        payload.put("has_attachments", !event.getMessage().getAttachments().isEmpty());
        payload.put("mentions_bot", mentionsBot);
        payload.put("content_preview", preview);
        String channelId = event.getChannel().getId();
        eventStore.record(new EventRecord("message", guildId, channelId, author.getId(), author.getName(), payload));
        if (mentionsBot) {
            eventStore.record(new EventRecord("bot_mention", guildId, channelId, author.getId(), author.getName(), payload));
        }
    }

    @Override
    public void onMessageDelete(MessageDeleteEvent event) {
        if (!event.isFromGuild()) {
            return;
        }
        String guildId = event.getGuild().getId();
        if (!guildGuard.isAllowed(guildId)) {
            return;
        }
        eventStore.record(new EventRecord("message_delete", guildId, event.getChannel().getId(), null, null,
                Map.of("message_id", event.getMessageId())));
    }

    @Override
    public void onGuildBan(GuildBanEvent event) {
        String guildId = event.getGuild().getId();
        if (!guildGuard.isAllowed(guildId)) {
            return;
        }
        User user = event.getUser();
        recordWithModerator("member_ban", guildId, user.getId(), user.getName(), null, event.getGuild(), ActionType.BAN);
    }

    @Override
    public void onGuildUnban(GuildUnbanEvent event) {
        String guildId = event.getGuild().getId();
        if (!guildGuard.isAllowed(guildId)) {
            return;
        }
        User user = event.getUser();
        eventStore.record(new EventRecord("member_unban", guildId, null, user.getId(), user.getName(), null));
    }

    @Override
    public void onGuildMemberUpdateTimeOut(GuildMemberUpdateTimeOutEvent event) {
        String guildId = event.getGuild().getId();
        if (!guildGuard.isAllowed(guildId)) {
            return;
        }
        Member member = event.getMember();
        OffsetDateTime until = event.getNewTimeOutEnd();
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("until", until == null ? null : until.toString());
        recordWithModerator("member_timeout", guildId, member.getId(), member.getUser().getName(), base,
                event.getGuild(), ActionType.MEMBER_UPDATE);
    }

    @Override
    public void onGuildMemberRoleAdd(GuildMemberRoleAddEvent event) {
        String guildId = event.getGuild().getId();
        if (!guildGuard.isAllowed(guildId)) {
            return;
        }
        Member member = event.getMember();
        List<String> names = event.getRoles().stream().map(Role::getName).toList();
        eventStore.record(new EventRecord("member_role_change", guildId, null, member.getId(), member.getUser().getName(),
                Map.of("added", names)));
    }

    @Override
    public void onGuildMemberRoleRemove(GuildMemberRoleRemoveEvent event) {
        String guildId = event.getGuild().getId();
        if (!guildGuard.isAllowed(guildId)) {
            return;
        }
        Member member = event.getMember();
        List<String> names = event.getRoles().stream().map(Role::getName).toList();
        eventStore.record(new EventRecord("member_role_change", guildId, null, member.getId(), member.getUser().getName(),
                Map.of("removed", names)));
    }

    @Override
    public void onChannelCreate(ChannelCreateEvent event) {
        if (!event.isFromGuild()) {
            return;
        }
        String guildId = event.getGuild().getId();
        if (!guildGuard.isAllowed(guildId)) {
            return;
        }
        Channel channel = event.getChannel();
        eventStore.record(new EventRecord("channel_create", guildId, channel.getId(), null, null,
                Map.of("name", channel.getName(), "channel_type", channel.getType().name())));
    }

    @Override
    public void onChannelDelete(ChannelDeleteEvent event) {
        if (!event.isFromGuild()) {
            return;
        }
        String guildId = event.getGuild().getId();
        if (!guildGuard.isAllowed(guildId)) {
            return;
        }
        Channel channel = event.getChannel();
        eventStore.record(new EventRecord("channel_delete", guildId, channel.getId(), null, null,
                Map.of("name", channel.getName(), "channel_type", channel.getType().name())));
    }

    private void recordWithModerator(String type, String guildId, String userId, String userName,
                                      Map<String, Object> basePayload, Guild guild, ActionType actionType) {
        guild.retrieveAuditLogs().type(actionType).limit(1).queue(
                entries -> eventStore.record(new EventRecord(type, guildId, null, userId, userName, withActor(basePayload, entries))),
                failure -> {
                    log.debug("Audit log lookup failed for {}: {}", type, failure.getMessage());
                    eventStore.record(new EventRecord(type, guildId, null, userId, userName, basePayload));
                });
    }

    private static Map<String, Object> withActor(Map<String, Object> base, List<AuditLogEntry> entries) {
        Map<String, Object> payload = base == null ? new LinkedHashMap<>() : new LinkedHashMap<>(base);
        if (entries.isEmpty()) {
            return payload;
        }
        User actor = entries.get(0).getUser();
        payload.put("actor_id", actor == null ? null : actor.getId());
        payload.put("actor_is_bot", actor != null && actor.isBot());
        return payload;
    }

    private static Map<String, Object> channelInfo(AudioChannelUnion channel) {
        if (channel == null) {
            return null;
        }
        return Map.of("id", channel.getId(), "name", channel.getName());
    }

    private static String activityTypeName(Activity activity) {
        return switch (activity.getType()) {
            case PLAYING -> "PLAYING";
            case STREAMING -> "STREAMING";
            default -> null;
        };
    }
}
