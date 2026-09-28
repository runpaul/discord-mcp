package dev.saseq.guards;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class GuildGuard {

	private final JDA jda;
	private final String defaultGuildId;
	private final Set<String> allowedGuildIds;

	public GuildGuard(JDA jda,
					   @Value("${DISCORD_GUILD_ID:}") String defaultGuildId,
					   @Value("${DISCORD_GUILD_ALLOWLIST:}") String allowlistCsv) {
		this.jda = jda;
		this.defaultGuildId = defaultGuildId;
		this.allowedGuildIds = buildAllowedSet(defaultGuildId, allowlistCsv);
	}

	public String resolveGuildId(String guildId) {
		if ((guildId == null || guildId.isBlank()) && defaultGuildId != null && !defaultGuildId.isBlank()) {
			return defaultGuildId.trim();
		}
		return guildId;
	}

	private static Set<String> buildAllowedSet(String defaultGuildId, String allowlistCsv) {
		Set<String> fromCsv = new LinkedHashSet<>();
		if (allowlistCsv != null && !allowlistCsv.isBlank()) {
			Arrays.stream(allowlistCsv.split(","))
					.map(String::trim)
					.filter(s -> !s.isEmpty())
					.forEach(fromCsv::add);
		}
		if (!fromCsv.isEmpty()) {
			return fromCsv;
		}
		Set<String> fromDefault = new LinkedHashSet<>();
		if (defaultGuildId != null && !defaultGuildId.isBlank()) {
			fromDefault.add(defaultGuildId.trim());
		}
		return fromDefault;
	}

	public boolean isAllowed(String guildId) {
		return guildId != null && allowedGuildIds.contains(guildId);
	}

	public void checkGuild(String guildId) {
		if (!isAllowed(guildId)) {
			throw new IllegalArgumentException("Guild " + guildId + " is not allowlisted");
		}
	}

	public void checkChannel(String channelId) {
		GuildChannel channel = jda.getGuildChannelById(channelId);
		if (channel == null) {
			return;
		}
		String guildId = channel.getGuild().getId();
		if (!isAllowed(guildId)) {
			throw new IllegalArgumentException("Channel " + channelId + " belongs to a guild that is not allowlisted");
		}
	}
}
