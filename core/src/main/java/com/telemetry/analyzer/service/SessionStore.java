package com.telemetry.analyzer.service;

import com.telemetry.analyzer.domain.LapData;
import com.telemetry.analyzer.domain.SessionData;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Imported sessions, kept in memory. With a sample budget, saving a session evicts the oldest
 * ones while the stored sessions together hold more samples than the budget, so repeated uploads
 * cannot exhaust the heap of a small server; the session just saved is always kept.
 */
public class SessionStore {

    private final Map<String, SessionData> sessions = new ConcurrentHashMap<>();
    private final long maxTotalSamples;
    /** Session ids, oldest first; guarded by {@code this}, as is {@link #totalSamples}. */
    private final Deque<String> saveOrder = new ArrayDeque<>();
    private long totalSamples;

    /** A store without a sample budget (desktop client, tests). */
    public SessionStore() {
        this(Long.MAX_VALUE);
    }

    public SessionStore(long maxTotalSamples) {
        if (maxTotalSamples <= 0) {
            throw new IllegalArgumentException("maxTotalSamples must be positive: " + maxTotalSamples);
        }
        this.maxTotalSamples = maxTotalSamples;
    }

    public synchronized void save(SessionData session) {
        SessionData replaced = sessions.put(session.sessionId(), session);
        if (replaced != null) {
            saveOrder.remove(replaced.sessionId());
            totalSamples -= sampleCount(replaced);
        }
        saveOrder.addLast(session.sessionId());
        totalSamples += sampleCount(session);
        while (totalSamples > maxTotalSamples && saveOrder.size() > 1) {
            SessionData evicted = sessions.remove(saveOrder.removeFirst());
            if (evicted != null) {
                totalSamples -= sampleCount(evicted);
            }
        }
    }

    public SessionData get(String sessionId) {
        return sessions.get(sessionId);
    }

    public Collection<SessionData> list() {
        return sessions.values();
    }

    private static long sampleCount(SessionData session) {
        long n = 0;
        for (LapData lap : session.laps()) {
            n += lap.points() == null ? 0 : lap.points().size();
        }
        return n;
    }
}
