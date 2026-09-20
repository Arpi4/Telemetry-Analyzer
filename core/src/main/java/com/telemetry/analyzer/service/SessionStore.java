package com.telemetry.analyzer.service;

import com.telemetry.analyzer.domain.SessionData;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SessionStore {

    private final Map<String, SessionData> sessions = new ConcurrentHashMap<>();

    public void save(SessionData session) {
        sessions.put(session.sessionId(), session);
    }

    public SessionData get(String sessionId) {
        return sessions.get(sessionId);
    }

    public Collection<SessionData> list() {
        return sessions.values();
    }
}