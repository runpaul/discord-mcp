package dev.saseq.guards;

import java.util.Locale;

public enum DestructiveMode {
	ALLOW,
	DRY_RUN,
	DENY,
	APPROVAL;

	public static DestructiveMode parse(String value) {
		if (value == null || value.isBlank()) {
			return DRY_RUN;
		}
		return switch (value.trim().toLowerCase(Locale.ROOT)) {
			case "allow" -> ALLOW;
			case "dry_run", "dry-run" -> DRY_RUN;
			case "deny" -> DENY;
			case "approval" -> APPROVAL;
			default -> throw new IllegalStateException("Unknown DESTRUCTIVE_MODE value: " + value);
		};
	}
}
