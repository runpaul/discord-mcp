package dev.saseq.guards;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TargetGuardTest {

    private static final String GUILD_ID = "1000";
    private static final String DEFAULT_GUILD_ID = "9999";

    private JDA jda;
    private Guild guild;
    private SelfMember selfMember;
    private TargetGuard targetGuard;

    @BeforeEach
    void setUp() {
        jda = mock(JDA.class);
        guild = mock(Guild.class);
        selfMember = mock(SelfMember.class);

        when(guild.getSelfMember()).thenReturn(selfMember);
        when(selfMember.getIdLong()).thenReturn(1L);
        when(jda.getGuildById(GUILD_ID)).thenReturn(guild);

        targetGuard = new TargetGuard(jda, DEFAULT_GUILD_ID);
    }

    private Member mockMember(long id, boolean canInteract) {
        Member member = mock(Member.class);
        when(member.getIdLong()).thenReturn(id);
        when(selfMember.canInteract(member)).thenReturn(canInteract);
        return member;
    }

    @Test
    void checkMember_ownerTarget_isRefused() {
        Member owner = mockMember(2L, true);
        when(guild.getOwnerIdLong()).thenReturn(2L);

        assertThrows(IllegalArgumentException.class, () -> targetGuard.checkMember(guild, owner));
    }

    @Test
    void checkMember_selfTarget_isRefused() {
        when(guild.getOwnerIdLong()).thenReturn(999L);
        Member self = mockMember(1L, true);

        assertThrows(IllegalArgumentException.class, () -> targetGuard.checkMember(guild, self));
    }

    @Test
    void checkMember_higherRoleTarget_isRefused() {
        when(guild.getOwnerIdLong()).thenReturn(999L);
        Member higherRoleMember = mockMember(3L, false);

        assertThrows(IllegalArgumentException.class, () -> targetGuard.checkMember(guild, higherRoleMember));
    }

    @Test
    void checkMember_regularMember_passes() {
        when(guild.getOwnerIdLong()).thenReturn(999L);
        Member regularMember = mockMember(3L, true);

        assertDoesNotThrow(() -> targetGuard.checkMember(guild, regularMember));
    }

    @Test
    void checkRole_roleAboveBot_isRefused() {
        Role role = mock(Role.class);
        when(selfMember.canInteract(role)).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> targetGuard.checkRole(guild, role));
    }

    @Test
    void checkRole_roleBelowBot_passes() {
        Role role = mock(Role.class);
        when(selfMember.canInteract(role)).thenReturn(true);

        assertDoesNotThrow(() -> targetGuard.checkRole(guild, role));
    }

    @Test
    void checkToolCall_nonGuardedTool_doesNothingAndNeverTouchesJda() {
        Map<String, Object> args = new HashMap<>();
        args.put("guildId", GUILD_ID);

        assertDoesNotThrow(() -> targetGuard.checkToolCall("send_message", args));

        verify(jda, never()).getGuildById(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void checkToolCall_usesDefaultGuild_whenGuildIdAbsent() {
        Guild defaultGuild = mock(Guild.class);
        SelfMember selfOfDefault = mock(SelfMember.class);
        when(defaultGuild.getSelfMember()).thenReturn(selfOfDefault);
        when(selfOfDefault.getIdLong()).thenReturn(1L);
        when(defaultGuild.getOwnerIdLong()).thenReturn(999L);
        when(jda.getGuildById(DEFAULT_GUILD_ID)).thenReturn(defaultGuild);

        Member target = mock(Member.class);
        when(target.getIdLong()).thenReturn(3L);
        when(selfOfDefault.canInteract(target)).thenReturn(true);

        CacheRestAction<Member> restAction = mock(CacheRestAction.class);
        when(defaultGuild.retrieveMemberById("42")).thenReturn(restAction);
        when(restAction.complete()).thenReturn(target);

        Map<String, Object> args = new HashMap<>();
        args.put("userId", "42");

        assertDoesNotThrow(() -> targetGuard.checkToolCall("kick_member", args));

        verify(jda).getGuildById(DEFAULT_GUILD_ID);
    }

    @Test
    @SuppressWarnings("unchecked")
    void checkToolCall_roleTool_roleAboveBot_isRefused() {
        Member target = mock(Member.class);
        when(target.getIdLong()).thenReturn(3L);
        when(guild.getOwnerIdLong()).thenReturn(999L);
        when(selfMember.canInteract(target)).thenReturn(true);

        Role role = mock(Role.class);
        when(guild.getRoleById("55")).thenReturn(role);
        when(selfMember.canInteract(role)).thenReturn(false);

        CacheRestAction<Member> restAction = mock(CacheRestAction.class);
        when(guild.retrieveMemberById("42")).thenReturn(restAction);
        when(restAction.complete()).thenReturn(target);

        Map<String, Object> args = new HashMap<>();
        args.put("guildId", GUILD_ID);
        args.put("userId", "42");
        args.put("roleId", "55");

        assertThrows(IllegalArgumentException.class, () -> targetGuard.checkToolCall("assign_role", args));
    }
}
