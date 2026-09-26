package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.concrete.StageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PresenceService {

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

    private final JDA jda;

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    public PresenceService(JDA jda) {
        this.jda = jda;
    }

    private String resolveGuildId(String guildId) {
        if ((guildId == null || guildId.isEmpty()) && defaultGuildId != null && !defaultGuildId.isEmpty()) {
            return defaultGuildId;
        }
        return guildId;
    }

    private Guild getGuild(String guildId) {
        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }
        return guild;
    }

    @Tool(name = "list_voice_members", description = "Lists members currently in voice and stage channels, with their mute/deafen/streaming/video status.")
    public String listVoiceMembers(
            @ToolParam(description = "Discord server ID", required = false) String guildId) {
        Guild guild = getGuild(guildId);
        List<String> lines = new ArrayList<>();

        boolean hasMembers = false;
        for (VoiceChannel vc : guild.getVoiceChannels()) {
            List<Member> members = vc.getMembers();
            if (!members.isEmpty()) {
                hasMembers = true;
                lines.add("**" + vc.getName() + "** (#" + vc.getId() + ")");
                for (Member member : members) {
                    lines.add(formatMember(member));
                }
            }
        }

        for (StageChannel sc : guild.getStageChannels()) {
            List<Member> members = sc.getMembers();
            if (!members.isEmpty()) {
                hasMembers = true;
                lines.add("**" + sc.getName() + " (Stage)** (#" + sc.getId() + ")");
                for (Member member : members) {
                    lines.add(formatMember(member));
                }
            }
        }

        if (!hasMembers) {
            return "No one is in voice.";
        }

        return String.join("\n", lines);
    }

    private String formatMember(Member member) {
        StringBuilder sb = new StringBuilder();
        sb.append("• ").append(member.getEffectiveName()).append(" (").append(member.getId()).append(")");

        List<String> flags = new ArrayList<>();
        if (member.getVoiceState() != null) {
            if (member.getVoiceState().isMuted()) {
                flags.add("muted");
            }
            if (member.getVoiceState().isDeafened()) {
                flags.add("deafened");
            }
            if (member.getVoiceState().isStream()) {
                flags.add("streaming");
            }
            if (member.getVoiceState().isSelfDeafened()) {
                flags.add("self-deafened");
            }
        }

        if (!flags.isEmpty()) {
            sb.append(" [").append(String.join(", ", flags)).append("]");
        }

        return sb.toString();
    }

    @Tool(name = "get_member_activities", description = "Lists members with active Discord activities (games, streaming, etc.).")
    public String getMemberActivities(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "Show only PLAYING and STREAMING activities (default: true)", required = false) String playingOnly) {

        Guild guild = getGuild(guildId);
        boolean filterPlayingOnly = playingOnly == null || playingOnly.isEmpty() || playingOnly.equals("true");

        List<Member> membersWithActivities = guild.getMembers().stream()
                .filter(m -> m.getActivities() != null && !m.getActivities().isEmpty())
                .collect(Collectors.toList());

        if (membersWithActivities.isEmpty()) {
            return "No members have activities.";
        }

        List<String> lines = new ArrayList<>();
        for (Member member : membersWithActivities) {
            for (Activity activity : member.getActivities()) {
                if (filterPlayingOnly) {
                    if (activity.getType() != Activity.ActivityType.PLAYING && activity.getType() != Activity.ActivityType.STREAMING) {
                        continue;
                    }
                }

                StringBuilder line = new StringBuilder();
                line.append(member.getEffectiveName()).append(" (").append(member.getId()).append("): ");
                line.append(activity.getName());

                line.append(" [").append(activity.getType().toString()).append("]");

                if (activity.getTimestamps() != null) {
                    Long startMs = activity.getTimestamps().getStart();
                    if (startMs != null && startMs.longValue() > 0) {
                        Duration duration = Duration.between(Instant.ofEpochMilli(startMs.longValue()), Instant.now());
                        line.append(" for ").append(formatDuration(duration));
                    }
                }

                lines.add(line.toString());
            }
        }

        if (lines.isEmpty()) {
            return "No members have activities matching the filter.";
        }

        lines.sort(String::compareTo);
        return String.join("\n", lines);
    }

    private String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;

        if (hours > 0) {
            return String.format("%dh%02dm", hours, minutes);
        } else {
            return String.format("%dm", minutes);
        }
    }
}
