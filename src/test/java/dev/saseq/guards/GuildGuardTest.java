package dev.saseq.guards;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuildGuardTest {

    private static final String DEFAULT_GUILD_ID = "1000";
    private static final String OTHER_GUILD_ID = "2000";

    @Test
    void isAllowed_usesDefaultGuildId_whenAllowlistEmpty() {
        JDA jda = mock(JDA.class);
        GuildGuard guard = new GuildGuard(jda, DEFAULT_GUILD_ID, "");

        assertTrue(guard.isAllowed(DEFAULT_GUILD_ID));
        assertFalse(guard.isAllowed(OTHER_GUILD_ID));
    }

    @Test
    void isAllowed_usesAllowlist_whenProvided() {
        JDA jda = mock(JDA.class);
        GuildGuard guard = new GuildGuard(jda, DEFAULT_GUILD_ID, "2000, 3000");

        assertFalse(guard.isAllowed(DEFAULT_GUILD_ID));
        assertTrue(guard.isAllowed(OTHER_GUILD_ID));
        assertTrue(guard.isAllowed("3000"));
    }

    @Test
    void checkGuild_notAllowlisted_isRejected() {
        JDA jda = mock(JDA.class);
        GuildGuard guard = new GuildGuard(jda, DEFAULT_GUILD_ID, "");

        assertThrows(IllegalArgumentException.class, () -> guard.checkGuild(OTHER_GUILD_ID));
        assertDoesNotThrow(() -> guard.checkGuild(DEFAULT_GUILD_ID));
    }

    @Test
    void checkChannel_inNonAllowlistedGuild_isRejected() {
        JDA jda = mock(JDA.class);
        GuildChannel channel = mock(GuildChannel.class);
        Guild otherGuild = mock(Guild.class);
        when(otherGuild.getId()).thenReturn(OTHER_GUILD_ID);
        when(channel.getGuild()).thenReturn(otherGuild);
        when(jda.getGuildChannelById("chan-1")).thenReturn(channel);

        GuildGuard guard = new GuildGuard(jda, DEFAULT_GUILD_ID, "");

        assertThrows(IllegalArgumentException.class, () -> guard.checkChannel("chan-1"));
    }

    @Test
    void checkChannel_inAllowlistedGuild_isAllowed() {
        JDA jda = mock(JDA.class);
        GuildChannel channel = mock(GuildChannel.class);
        Guild guild = mock(Guild.class);
        when(guild.getId()).thenReturn(DEFAULT_GUILD_ID);
        when(channel.getGuild()).thenReturn(guild);
        when(jda.getGuildChannelById("chan-1")).thenReturn(channel);

        GuildGuard guard = new GuildGuard(jda, DEFAULT_GUILD_ID, "");

        assertDoesNotThrow(() -> guard.checkChannel("chan-1"));
    }

    @Test
    void checkChannel_notFound_letsToolHandleIt() {
        JDA jda = mock(JDA.class);
        when(jda.getGuildChannelById("missing")).thenReturn(null);

        GuildGuard guard = new GuildGuard(jda, DEFAULT_GUILD_ID, "");

        assertDoesNotThrow(() -> guard.checkChannel("missing"));
    }

    @Test
    void resolveGuildId_fallsBackToDefault_whenArgBlank() {
        JDA jda = mock(JDA.class);
        GuildGuard guard = new GuildGuard(jda, DEFAULT_GUILD_ID, "");

        assertTrue(guard.resolveGuildId(null).equals(DEFAULT_GUILD_ID));
        assertTrue(guard.resolveGuildId("").equals(DEFAULT_GUILD_ID));
        assertTrue(guard.resolveGuildId(OTHER_GUILD_ID).equals(OTHER_GUILD_ID));
    }
}
