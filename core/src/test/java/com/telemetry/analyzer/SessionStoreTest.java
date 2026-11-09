package com.telemetry.analyzer;

import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;
import com.telemetry.analyzer.domain.TelemetryPoint;
import com.telemetry.analyzer.service.SessionStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionStoreTest {

    @Test
    void evictsOldestSessionsBeyondTheSampleBudget() {
        SessionStore store = new SessionStore(250);
        store.save(session("a", 100));
        store.save(session("b", 100));
        assertEquals(Set.of("a", "b"), ids(store));

        store.save(session("c", 100));     // 300 > 250: "a" goes
        assertEquals(Set.of("b", "c"), ids(store));
        assertNull(store.get("a"));

        store.save(session("b", 40));      // replaces "b": 140, nothing evicted
        assertEquals(Set.of("b", "c"), ids(store));
        assertEquals(40, store.get("b").laps().get(0).points().size());

        store.save(session("d", 200));     // 340: "c" goes (oldest now), then 240 fits
        assertEquals(Set.of("b", "d"), ids(store));
    }

    @Test
    void keepsTheNewestSessionEvenWhenItAloneExceedsTheBudget() {
        SessionStore store = new SessionStore(50);
        store.save(session("a", 10));
        store.save(session("big", 80));
        assertEquals(Set.of("big"), ids(store));
        assertNotNull(store.get("big"));
    }

    @Test
    void defaultStoreIsUnbounded() {
        SessionStore store = new SessionStore();
        for (int i = 0; i < 20; i++) {
            store.save(session("s" + i, 1000));
        }
        assertEquals(20, store.list().size());
        assertThrows(IllegalArgumentException.class, () -> new SessionStore(0));
    }

    private static Set<String> ids(SessionStore store) {
        return store.list().stream().map(SessionData::sessionId).collect(Collectors.toSet());
    }

    private static SessionData session(String id, int samples) {
        List<TelemetryPoint> points = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            points.add(new TelemetryPoint(i * 0.1, 100, 50, 0, 3, null, null, (double) i));
        }
        return new SessionData(id, id + ".csv", Instant.now(), List.of(new LapData("lap-1", points)));
    }
}
