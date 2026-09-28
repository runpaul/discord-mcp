package dev.saseq.events;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Debounces activity_start/activity_end pairs for the same (userId, activity name).
 * A start is held for {@code window}; if the matching end arrives within that window both are
 * dropped (a blip). Otherwise the start is emitted once the window elapses (payload gets
 * {@code started_at}), and a later end is emitted normally.
 */
public class ActivityDebouncer {

    private final EventStore store;
    private final Clock clock;
    private final Duration window;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "activity-debouncer");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public ActivityDebouncer(EventStore store) {
        this(store, Clock.systemUTC(), Duration.ofSeconds(60));
    }

    ActivityDebouncer(EventStore store, Clock clock, Duration window) {
        this.store = store;
        this.clock = clock;
        this.window = window;
    }

    private record Pending(String guildId, String channelId, String userId, String userName,
                            String activityType, String activityName, Instant startedAt) {
    }

    public void onActivityStart(String guildId, String channelId, String userId, String userName,
                                 String activityType, String activityName) {
        String key = key(userId, activityName);
        Pending entry = new Pending(guildId, channelId, userId, userName, activityType, activityName, clock.instant());
        pending.put(key, entry);
        scheduler.schedule(() -> emitIfStillPending(key, entry), window.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void onActivityEnd(String guildId, String channelId, String userId, String userName,
                               String activityType, String activityName) {
        String key = key(userId, activityName);
        if (pending.remove(key) != null) {
            return;
        }
        store.record(new EventRecord("activity_end", guildId, channelId, userId, userName,
                Map.of("name", activityName, "activity_type", activityType)));
    }

    private void emitIfStillPending(String key, Pending entry) {
        if (!pending.remove(key, entry)) {
            return;
        }
        store.record(new EventRecord("activity_start", entry.guildId(), entry.channelId(), entry.userId(), entry.userName(),
                Map.of("name", entry.activityName(), "activity_type", entry.activityType(),
                        "started_at", entry.startedAt().toString())));
    }

    private static String key(String userId, String activityName) {
        return userId + "|" + activityName;
    }
}
