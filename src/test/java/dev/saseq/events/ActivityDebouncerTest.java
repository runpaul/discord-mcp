package dev.saseq.events;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class ActivityDebouncerTest {

    private static final Duration WINDOW = Duration.ofMillis(150);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void startThenEndWithinWindow_emitsNothing() throws InterruptedException {
        EventStore store = mock(EventStore.class);
        ActivityDebouncer debouncer = new ActivityDebouncer(store, CLOCK, WINDOW);

        debouncer.onActivityStart("g1", null, "u1", "alice", "PLAYING", "Chess");
        debouncer.onActivityEnd("g1", null, "u1", "alice", "PLAYING", "Chess");

        Thread.sleep(WINDOW.toMillis() + 100);
        verify(store, never()).record(any());
    }

    @Test
    void startAlone_isEmittedAfterWindow() {
        EventStore store = mock(EventStore.class);
        ActivityDebouncer debouncer = new ActivityDebouncer(store, CLOCK, WINDOW);

        debouncer.onActivityStart("g1", null, "u1", "alice", "PLAYING", "Chess");

        verify(store, timeout(WINDOW.toMillis() + 500)).record(argThat(r ->
                "activity_start".equals(r.type())
                        && "u1".equals(r.userId())
                        && "Chess".equals(r.payload().get("name"))
                        && r.payload().containsKey("started_at")));
    }

    @Test
    void endAfterStartEmitted_isEmittedNormally() {
        EventStore store = mock(EventStore.class);
        ActivityDebouncer debouncer = new ActivityDebouncer(store, CLOCK, WINDOW);

        debouncer.onActivityStart("g1", null, "u1", "alice", "PLAYING", "Chess");
        verify(store, timeout(WINDOW.toMillis() + 500)).record(argThat(r -> "activity_start".equals(r.type())));

        debouncer.onActivityEnd("g1", null, "u1", "alice", "PLAYING", "Chess");
        verify(store, timeout(500)).record(argThat(r -> "activity_end".equals(r.type()) && "u1".equals(r.userId())));
    }
}
