package org.booklore.komga.session;

import org.booklore.komga.config.ConditionalOnKomgaEnabled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory Komga API sessions: random token → OPDS account. A session expires after
 * {@link #IDLE_TIMEOUT} without use, and is only valid while the account keeps the password
 * it had when the session was opened. Sessions are lost on restart; clients then authenticate again.
 */
@ConditionalOnKomgaEnabled
@Component
public class KomgaSessionStore {

    /** Same lifetime as Komga's {@code KOMGA-SESSION}. */
    public static final Duration IDLE_TIMEOUT = Duration.ofDays(7);
    static final int MAX_SESSIONS = 10_000;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Clock clock;

    public KomgaSessionStore() {
        this(Clock.systemUTC());
    }

    KomgaSessionStore(Clock clock) {
        this.clock = clock;
    }

    /** A session of an OPDS account. */
    public record Session(String username, String credentialFingerprint, Instant lastUsed) {

        /** True while the account still has the password the session was opened with. */
        public boolean matches(String passwordHash) {
            return MessageDigest.isEqual(
                    credentialFingerprint.getBytes(StandardCharsets.US_ASCII),
                    fingerprint(passwordHash).getBytes(StandardCharsets.US_ASCII));
        }
    }

    /** Opens a session and returns its token. */
    public String open(String username, String passwordHash) {
        Instant now = clock.instant();
        if (sessions.size() >= MAX_SESSIONS) {
            evict(now);
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, new Session(username, fingerprint(passwordHash), now));
        return token;
    }

    /** The live session of the token, its idle timer restarted; empty when unknown or expired. */
    public Optional<Session> use(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Session session = sessions.computeIfPresent(token,
                (key, current) -> expired(current, now) ? null : new Session(current.username(), current.credentialFingerprint(), now));
        return Optional.ofNullable(session);
    }

    /** Closes a session (stale credentials). */
    public void close(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    int size() {
        return sessions.size();
    }

    /** Drops expired sessions, then the least recently used ones if the store is still full. */
    private void evict(Instant now) {
        sessions.values().removeIf(session -> expired(session, now));
        int excess = sessions.size() - MAX_SESSIONS + 1;
        if (excess > 0) {
            sessions.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getValue().lastUsed()))
                    .limit(excess)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(sessions::remove);
        }
    }

    private static boolean expired(Session session, Instant now) {
        return session.lastUsed().plus(IDLE_TIMEOUT).isBefore(now);
    }

    private static String fingerprint(String passwordHash) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(passwordHash).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
