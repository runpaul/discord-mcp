package dev.saseq.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly retention sweep for the event store. Scheduling itself is enabled by the
 * orchestrator ({@code @EnableScheduling} lives there, not here).
 */
@Component
public class EventMaintenance {
    private static final Logger log = LoggerFactory.getLogger(EventMaintenance.class);

    private final EventStore eventStore;

    public EventMaintenance(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void pruneOldEvents() {
        int removed = eventStore.prune();
        log.info("Scheduled maintenance pruned {} events (retention {} days)", removed, eventStore.retentionDays());
    }
}
