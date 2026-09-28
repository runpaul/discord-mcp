package dev.saseq.events;

import java.util.Map;

/**
 * One Discord gateway event to persist. {@code ts} is assigned by the store (UTC now).
 * Nullable ids are allowed; payload is serialized to compact JSON.
 */
public record EventRecord(String type,
                          String guildId,
                          String channelId,
                          String userId,
                          String userName,
                          Map<String, Object> payload) {
}
