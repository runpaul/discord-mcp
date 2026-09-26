package dev.saseq.guards;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class TargetGuard {

    public static final Set<String> MEMBER_TOOLS = Set.of(
            "kick_member", "ban_member", "timeout_member", "set_nickname",
            "assign_role", "remove_role", "move_member", "disconnect_member",
            "modify_voice_state"
    );

    public static final Set<String> ROLE_TOOLS = Set.of("assign_role", "remove_role");

    private final JDA jda;

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    public TargetGuard(JDA jda, @Value("${DISCORD_GUILD_ID:}") String defaultGuildId) {
        this.jda = jda;
        this.defaultGuildId = defaultGuildId;
    }

    public void checkMember(Guild guild, Member target) {
        if (guild.getOwnerIdLong() == target.getIdLong()) {
            throw new IllegalArgumentException("Cannot target the guild owner");
        }
        if (guild.getSelfMember().getIdLong() == target.getIdLong()) {
            throw new IllegalArgumentException("Cannot target the bot itself");
        }
        if (!guild.getSelfMember().canInteract(target)) {
            throw new IllegalArgumentException("Cannot target this user - they have a higher or equal role than the bot");
        }
    }

    public void checkRole(Guild guild, Role role) {
        if (!guild.getSelfMember().canInteract(role)) {
            throw new IllegalArgumentException("Cannot use this role - it is higher in the hierarchy than the bot's highest role");
        }
    }

    public void checkToolCall(String toolName, Map<String, Object> args) {
        if (!MEMBER_TOOLS.contains(toolName)) {
            return;
        }

        String guildId = resolveGuildId((String) args.get("guildId"));
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }

        Object userIdObj = args.get("userId");
        String userId = userIdObj == null ? null : userIdObj.toString();
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId cannot be null");
        }

        Member member;
        try {
            member = guild.retrieveMemberById(userId).complete();
        } catch (ErrorResponseException e) {
            throw new IllegalArgumentException("User not found");
        }
        checkMember(guild, member);

        if (ROLE_TOOLS.contains(toolName)) {
            Object roleIdObj = args.get("roleId");
            String roleId = roleIdObj == null ? null : roleIdObj.toString();
            if (roleId == null || roleId.isEmpty()) {
                throw new IllegalArgumentException("roleId cannot be null");
            }
            Role role = guild.getRoleById(roleId);
            if (role == null) {
                throw new IllegalArgumentException("Role not found by roleId");
            }
            checkRole(guild, role);
        }
    }

    private String resolveGuildId(String guildId) {
        if ((guildId == null || guildId.isEmpty()) && defaultGuildId != null && !defaultGuildId.isEmpty()) {
            return defaultGuildId;
        }
        return guildId;
    }
}
