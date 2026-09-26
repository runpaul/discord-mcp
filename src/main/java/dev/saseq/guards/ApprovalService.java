package dev.saseq.guards;

import dev.saseq.events.EventStore;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.util.json.JsonParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * Implements DESTRUCTIVE_MODE=approval: a destructive tool call is parked as a
 * {@code pending_actions} row, posted to a private Discord channel as an embed with
 * &#9989;/&#10060; reactions, and only executed once a configured approver reacts &#9989;.
 * Rejections, expirations and double-decisions are all handled without ever running the
 * underlying tool twice.
 */
@Component
public class ApprovalService {

	private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

	private static final String APPROVE_EMOJI = "✅"; // white heavy check mark
	private static final String REJECT_EMOJI = "❌"; // cross mark
	private static final int RESULT_MAX_LEN = 1000;

	private final JDA jda;
	private final EventStore eventStore;
	private final GuildGuard guildGuard;
	private final TargetGuard targetGuard;
	private final String channelId;
	private final Set<String> approverIds;
	private final int ttlHours;

	private final Map<String, ToolCallback> delegates = new ConcurrentHashMap<>();

	public ApprovalService(JDA jda,
						   EventStore eventStore,
						   GuildGuard guildGuard,
						   TargetGuard targetGuard,
						   @Value("${APPROVAL_CHANNEL_ID:}") String channelId,
						   @Value("${APPROVER_USER_IDS:}") String approversCsv,
						   @Value("${APPROVAL_TTL_HOURS:24}") int ttlHours) {
		this.jda = jda;
		this.eventStore = eventStore;
		this.guildGuard = guildGuard;
		this.targetGuard = targetGuard;
		this.channelId = channelId;
		this.approverIds = parseApprovers(approversCsv);
		this.ttlHours = ttlHours;
	}

	private static Set<String> parseApprovers(String approversCsv) {
		Set<String> ids = new LinkedHashSet<>();
		if (approversCsv != null && !approversCsv.isBlank()) {
			Arrays.stream(approversCsv.split(","))
					.map(String::trim)
					.filter(s -> !s.isEmpty())
					.forEach(ids::add);
		}
		return ids;
	}

	/** Registers the unwrapped callback used to actually execute a tool once approved. */
	public void registerDelegate(String tool, ToolCallback rawDelegate) {
		delegates.put(tool, rawDelegate);
	}

	/**
	 * Parks a destructive call for human approval: stores a pending row, posts an embed
	 * with the reaction prompts, and returns the placeholder message the agent should show.
	 */
	public String requestApproval(String tool, String inputJson, String summary, String reason) {
		if (channelId == null || channelId.isBlank() || approverIds.isEmpty()) {
			throw new IllegalStateException("approval mode needs APPROVAL_CHANNEL_ID and APPROVER_USER_IDS");
		}

		Instant requestedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		Instant expiresAt = requestedAt.plus(ttlHours, ChronoUnit.HOURS);
		String requestedAtStr = requestedAt.toString();
		String expiresAtStr = expiresAt.toString();

		long id = insertPending(tool, inputJson, summary, requestedAtStr, expiresAtStr);

		EmbedBuilder embed = new EmbedBuilder()
				.setTitle("Agent requests #" + id)
				.setDescription(summary)
				.addField("Reason", reason == null || reason.isBlank() ? "none" : reason, false)
				.setFooter("✅ approve · ❌ reject · expires " + expiresAtStr);

		TextChannel channel = jda.getTextChannelById(channelId);
		if (channel == null) {
			throw new IllegalStateException("Approval channel not found: " + channelId);
		}
		Message posted = channel.sendMessageEmbeds(embed.build()).complete();
		posted.addReaction(Emoji.fromUnicode(APPROVE_EMOJI)).queue();
		posted.addReaction(Emoji.fromUnicode(REJECT_EMOJI)).queue();
		storeApprovalMessageId(id, posted.getId());

		return "PENDING_APPROVAL #" + id + ": waiting for a human. Do not retry.";
	}

	/** Called by {@link dev.saseq.events.ApprovalReactionListener} for every reaction add. */
	public void onReaction(String reactedChannelId, String messageId, String userId, String emoji) {
		if (channelId == null || !channelId.equals(reactedChannelId)) {
			return;
		}
		if (!approverIds.contains(userId)) {
			log.info("Ignoring approval reaction from non-approver user={}", userId);
			return;
		}
		if (jda.getSelfUser().getId().equals(userId)) {
			return;
		}
		if (!APPROVE_EMOJI.equals(emoji) && !REJECT_EMOJI.equals(emoji)) {
			return;
		}

		Optional<PendingRow> maybeRow = findPendingRowByMessageId(messageId);
		if (maybeRow.isEmpty()) {
			return;
		}
		PendingRow row = maybeRow.get();
		if (!"pending".equals(row.status()) || isExpired(row.expiresAt())) {
			return;
		}

		if (REJECT_EMOJI.equals(emoji)) {
			handleReject(row, messageId, userId);
		} else {
			handleApprove(row, messageId, userId);
		}
	}

	private void handleReject(PendingRow row, String messageId, String userId) {
		int updated = updateStatusIfPending(row.id(), "rejected", userId, null);
		if (updated == 0) {
			return;
		}
		editEmbed(messageId, row.id(), "❌ Rejected by <@" + userId + ">");
		log.info("tool={} id={} decided_by={} outcome=rejected", row.tool(), row.id(), userId);
	}

	private void handleApprove(PendingRow row, String messageId, String userId) {
		int updated = updateStatusIfPending(row.id(), "approved", userId, null);
		if (updated == 0) {
			// Already decided by someone else - never execute twice.
			return;
		}
		executeApproved(row, messageId, userId);
	}

	private void executeApproved(PendingRow row, String messageId, String userId) {
		String outcome;
		String result;
		try {
			Map<String, Object> args = parseArgs(row.inputJson());
			runGuards(row.tool(), args);
			ToolCallback delegate = delegates.get(row.tool());
			if (delegate == null) {
				throw new IllegalStateException("No delegate registered for tool " + row.tool());
			}
			String raw = delegate.call(row.inputJson());
			result = truncate(raw);
			outcome = "done";
		} catch (IllegalArgumentException | IllegalStateException refusal) {
			result = truncate(refusal.getMessage());
			outcome = "failed";
		} catch (RuntimeException e) {
			result = truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
			outcome = "failed";
		}
		finalizeStatus(row.id(), outcome, userId, result);
		editEmbed(messageId, row.id(), describeOutcome(outcome, userId, result));
		log.info("tool={} id={} decided_by={} outcome={}", row.tool(), row.id(), userId, outcome);
	}

	private void runGuards(String tool, Map<String, Object> args) {
		if (!GuardedToolCallback.DM_TOOLS.contains(tool)) {
			String guildId = guildGuard.resolveGuildId(asString(args.get("guildId")));
			guildGuard.checkGuild(guildId);
			String channelArg = asString(args.get("channelId"));
			if (channelArg != null && !channelArg.isBlank()) {
				guildGuard.checkChannel(channelArg);
			}
		}
		targetGuard.checkToolCall(tool, args);
	}

	private static String describeOutcome(String outcome, String userId, String result) {
		return "done".equals(outcome)
				? "✅ Approved by <@" + userId + "> - " + result
				: "⚠️ Failed after approval by <@" + userId + "> - " + result;
	}

	/** Best-effort expiry sweep. Scheduling itself is enabled by the orchestrator. */
	@Scheduled(fixedDelay = 3600000, initialDelay = 60000)
	public int expirePending() {
		try {
			List<ExpiredRow> expired = eventStore.submitWrite(this::expireOverdueRows).get();
			expired.forEach(row -> editEmbed(row.approvalMessageId(), row.id(), "expired"));
			return expired.size();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return 0;
		} catch (ExecutionException e) {
			log.warn("expirePending failed: {}", e.getMessage());
			return 0;
		}
	}

	private List<ExpiredRow> expireOverdueRows() throws SQLException {
		String now = Instant.now().toString();
		List<ExpiredRow> rows = new ArrayList<>();
		String selectSql = "SELECT id, approval_message_id FROM pending_actions WHERE status='pending' AND expires_at <= ?";
		try (Connection c = eventStore.openConnection(); PreparedStatement ps = c.prepareStatement(selectSql)) {
			ps.setString(1, now);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					rows.add(new ExpiredRow(rs.getLong("id"), rs.getString("approval_message_id")));
				}
			}
		}
		if (!rows.isEmpty()) {
			String updateSql = "UPDATE pending_actions SET status='expired' WHERE status='pending' AND expires_at <= ?";
			try (Connection c = eventStore.openConnection(); PreparedStatement ps = c.prepareStatement(updateSql)) {
				ps.setString(1, now);
				ps.executeUpdate();
			}
		}
		return rows;
	}

	private long insertPending(String tool, String inputJson, String summary, String requestedAt, String expiresAt) {
		try {
			return eventStore.submitWrite(() -> {
				String sql = "INSERT INTO pending_actions(requested_at, expires_at, tool, input_json, summary, status) "
						+ "VALUES(?,?,?,?,?, 'pending')";
				try (Connection c = eventStore.openConnection();
					 PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
					ps.setString(1, requestedAt);
					ps.setString(2, expiresAt);
					ps.setString(3, tool);
					ps.setString(4, inputJson);
					ps.setString(5, summary);
					ps.executeUpdate();
					try (ResultSet keys = ps.getGeneratedKeys()) {
						if (keys.next()) {
							return keys.getLong(1);
						}
						throw new SQLException("No generated key for pending_actions insert");
					}
				}
			}).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while storing pending action", e);
		} catch (ExecutionException e) {
			throw new IllegalStateException("Failed to store pending action", e.getCause() != null ? e.getCause() : e);
		}
	}

	private void storeApprovalMessageId(long id, String approvalMessageId) {
		runWrite(() -> {
			try (Connection c = eventStore.openConnection();
				 PreparedStatement ps = c.prepareStatement("UPDATE pending_actions SET approval_message_id=? WHERE id=?")) {
				ps.setString(1, approvalMessageId);
				ps.setLong(2, id);
				ps.executeUpdate();
				return null;
			}
		});
	}

	private int updateStatusIfPending(long id, String status, String decidedBy, String result) {
		return runWrite(() -> {
			try (Connection c = eventStore.openConnection();
				 PreparedStatement ps = c.prepareStatement(
						 "UPDATE pending_actions SET status=?, decided_by=?, result=? WHERE id=? AND status='pending'")) {
				ps.setString(1, status);
				ps.setString(2, decidedBy);
				ps.setString(3, result);
				ps.setLong(4, id);
				return ps.executeUpdate();
			}
		});
	}

	private void finalizeStatus(long id, String status, String decidedBy, String result) {
		runWrite(() -> {
			try (Connection c = eventStore.openConnection();
				 PreparedStatement ps = c.prepareStatement(
						 "UPDATE pending_actions SET status=?, decided_by=?, result=? WHERE id=?")) {
				ps.setString(1, status);
				ps.setString(2, decidedBy);
				ps.setString(3, result);
				ps.setLong(4, id);
				ps.executeUpdate();
				return null;
			}
		});
	}

	private Optional<PendingRow> findPendingRowByMessageId(String approvalMessageId) {
		String sql = "SELECT id, tool, input_json, summary, expires_at, status FROM pending_actions WHERE approval_message_id = ?";
		try (Connection c = eventStore.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setString(1, approvalMessageId);
			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next()) {
					return Optional.empty();
				}
				return Optional.of(new PendingRow(rs.getLong("id"), rs.getString("tool"), rs.getString("input_json"),
						rs.getString("summary"), rs.getString("expires_at"), rs.getString("status")));
			}
		} catch (SQLException e) {
			log.warn("Failed to look up pending action for message {}: {}", approvalMessageId, e.getMessage());
			return Optional.empty();
		}
	}

	private <T> T runWrite(java.util.concurrent.Callable<T> work) {
		try {
			return eventStore.submitWrite(work).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while writing pending action", e);
		} catch (ExecutionException e) {
			throw new IllegalStateException("Failed to write pending action", e.getCause() != null ? e.getCause() : e);
		}
	}

	private void editEmbed(String messageId, long pendingId, String footerLine) {
		if (messageId == null || messageId.isBlank()) {
			return;
		}
		try {
			TextChannel channel = jda.getTextChannelById(channelId);
			if (channel == null) {
				return;
			}
			Message message = channel.retrieveMessageById(messageId).complete();
			if (message == null) {
				return;
			}
			EmbedBuilder builder = message.getEmbeds().isEmpty()
					? new EmbedBuilder()
					: new EmbedBuilder(message.getEmbeds().get(0));
			builder.setFooter(footerLine);
			message.editMessageEmbeds(builder.build()).complete();
		} catch (RuntimeException e) {
			log.debug("Failed to update approval embed #{}: {}", pendingId, e.getMessage());
		}
	}

	private static boolean isExpired(String expiresAt) {
		try {
			return !Instant.parse(expiresAt).isAfter(Instant.now());
		} catch (RuntimeException e) {
			return true;
		}
	}

	private static String truncate(String value) {
		if (value == null) {
			return null;
		}
		return value.length() > RESULT_MAX_LEN ? value.substring(0, RESULT_MAX_LEN) : value;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parseArgs(String input) {
		if (input == null || input.isBlank()) {
			return new LinkedHashMap<>();
		}
		Map<String, Object> parsed = JsonParser.fromJson(input, Map.class);
		return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
	}

	private static String asString(Object value) {
		return value == null ? null : value.toString();
	}

	private record PendingRow(long id, String tool, String inputJson, String summary, String expiresAt, String status) {
	}

	private record ExpiredRow(long id, String approvalMessageId) {
	}
}
