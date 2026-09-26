package dev.saseq.services;

import dev.saseq.events.EventStore;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only visibility into the DESTRUCTIVE_MODE=approval queue: lets the agent (or a human
 * asking the agent) check what is parked, decided, or expired without touching Discord.
 */
@Service
public class PendingActionsService {

	private static final int MAX_ROWS = 50;
	private static final int RESULT_PREVIEW_MAX = 200;

	private final EventStore eventStore;

	public PendingActionsService(EventStore eventStore) {
		this.eventStore = eventStore;
	}

	@Tool(name = "get_pending_actions", description = "List parked destructive tool calls awaiting human approval (DESTRUCTIVE_MODE=approval)")
	public String getPendingActions(
			@ToolParam(description = "Filter by status: pending|approved|rejected|expired|failed|done, or \"all\". Defaults to \"pending\"", required = false) String status) {
		String filter = (status == null || status.isBlank()) ? "pending" : status.trim().toLowerCase(java.util.Locale.ROOT);

		List<String> rows = new ArrayList<>();
		String sql = "all".equals(filter)
				? "SELECT id, status, tool, summary, requested_at, expires_at, decided_by, result FROM pending_actions ORDER BY id DESC LIMIT ?"
				: "SELECT id, status, tool, summary, requested_at, expires_at, decided_by, result FROM pending_actions WHERE status = ? ORDER BY id DESC LIMIT ?";

		try (Connection c = eventStore.openConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
			if ("all".equals(filter)) {
				ps.setInt(1, MAX_ROWS);
			} else {
				ps.setString(1, filter);
				ps.setInt(2, MAX_ROWS);
			}
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					rows.add(formatRow(rs));
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("Failed to read pending actions: " + e.getMessage(), e);
		}

		if (rows.isEmpty()) {
			return "No pending actions with status=" + filter;
		}
		return String.join("\n", rows);
	}

	private static String formatRow(ResultSet rs) throws SQLException {
		String result = rs.getString("result");
		String resultPreview = result == null
				? ""
				: (result.length() > RESULT_PREVIEW_MAX ? result.substring(0, RESULT_PREVIEW_MAX) : result);
		return "#" + rs.getLong("id") + " " + rs.getString("status") + " " + rs.getString("tool") + " "
				+ rs.getString("summary") + " " + rs.getString("requested_at") + " " + rs.getString("expires_at") + " "
				+ rs.getString("decided_by") + " " + resultPreview;
	}
}
