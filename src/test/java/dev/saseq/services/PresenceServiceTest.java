package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.concrete.StageChannel;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PresenceServiceTest {

    private static final String GUILD_ID = "1000";
    private static final String DEFAULT_GUILD_ID = "9999";

    private JDA jda;
    private Guild guild;
    private PresenceService presenceService;

    @BeforeEach
    void setUp() {
        jda = mock(JDA.class);
        guild = mock(Guild.class);

        when(guild.getVoiceChannels()).thenReturn(List.of());
        when(guild.getStageChannels()).thenReturn(List.of());
        when(guild.getMembers()).thenReturn(List.of());
        when(jda.getGuildById(GUILD_ID)).thenReturn(guild);

        presenceService = new PresenceService(jda);
    }

    @Test
    @DisplayName("list_voice_members with empty voice returns 'No one is in voice'")
    void testListVoiceMembersEmpty() {
        String result = presenceService.listVoiceMembers(GUILD_ID);
        assertTrue(result.contains("No one is in voice"));
    }

    @Test
    @DisplayName("list_voice_members lists members with flags (muted, deafened)")
    void testListVoiceMembersWithFlags() {
        VoiceChannel vc = mock(VoiceChannel.class);
        when(vc.getName()).thenReturn("General");
        when(vc.getId()).thenReturn("1001");

        Member member = mock(Member.class);
        when(member.getEffectiveName()).thenReturn("TestUser");
        when(member.getId()).thenReturn("1002");

        GuildVoiceState voiceState = mock(GuildVoiceState.class);
        when(voiceState.isMuted()).thenReturn(true);
        when(voiceState.isDeafened()).thenReturn(false);
        when(voiceState.isStream()).thenReturn(false);
        when(voiceState.isSelfDeafened()).thenReturn(false);
        when(member.getVoiceState()).thenReturn(voiceState);

        when(vc.getMembers()).thenReturn(List.of(member));
        when(guild.getVoiceChannels()).thenReturn(List.of(vc));

        String result = presenceService.listVoiceMembers(GUILD_ID);
        assertTrue(result.contains("TestUser"));
        assertTrue(result.contains("muted"));
        assertTrue(result.contains("General"));
    }

    @Test
    @DisplayName("get_member_activities filters by playingOnly when true")
    void testGetMemberActivitiesPlayingOnlyFilter() {
        Member member = mock(Member.class);
        when(member.getEffectiveName()).thenReturn("Gamer");
        when(member.getId()).thenReturn("1002");

        Activity playingActivity = mock(Activity.class);
        when(playingActivity.getType()).thenReturn(Activity.ActivityType.PLAYING);
        when(playingActivity.getName()).thenReturn("Elden Ring");
        when(playingActivity.getTimestamps()).thenReturn(null);

        Activity watchingActivity = mock(Activity.class);
        when(watchingActivity.getType()).thenReturn(Activity.ActivityType.WATCHING);
        when(watchingActivity.getName()).thenReturn("YouTube");
        when(watchingActivity.getTimestamps()).thenReturn(null);

        when(member.getActivities()).thenReturn(List.of(playingActivity, watchingActivity));
        when(guild.getMembers()).thenReturn(List.of(member));

        String result = presenceService.getMemberActivities(GUILD_ID, "true");
        assertTrue(result.contains("Elden Ring"));
        assertTrue(result.contains("PLAYING"));
        assertTrue(!result.contains("YouTube") || result.contains("No members have activities matching"));
    }

    @Test
    @DisplayName("get_member_activities with no members returns appropriate message")
    void testGetMemberActivitiesEmpty() {
        String result = presenceService.getMemberActivities(GUILD_ID, "true");
        assertTrue(result.contains("No members have activities") || result.contains("matching the filter"));
    }

    @Test
    @DisplayName("getGuild throws when guild not found")
    void testGetGuildNotFound() {
        when(jda.getGuildById("invalid")).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> presenceService.listVoiceMembers("invalid"));
    }
}
