package org.booklore.komga.session;

import org.booklore.komga.core.KomgaUserGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.http.Cookie;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KomgaSessionTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final KomgaSessionStore store = new KomgaSessionStore(clock);
    private final KomgaUserGateway userGateway = mock(KomgaUserGateway.class);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ==================== Store ====================

    @Test
    void sessionLivesWhileUsedAndExpiresAfterSevenIdleDays() {
        String token = store.open("reader", "hash");

        clock.set(NOW.plus(KomgaSessionStore.IDLE_TIMEOUT).minusSeconds(1));
        assertThat(store.use(token)).hasValueSatisfying(s -> assertThat(s.username()).isEqualTo("reader"));

        // Using it restarted the idle timer
        clock.set(NOW.plus(KomgaSessionStore.IDLE_TIMEOUT.multipliedBy(2)).minusSeconds(2));
        assertThat(store.use(token)).isPresent();

        clock.set(NOW.plus(KomgaSessionStore.IDLE_TIMEOUT.multipliedBy(3)));
        assertThat(store.use(token)).isEmpty();
        assertThat(store.size()).isZero();
    }

    @Test
    void tokensAreRandomAndUnknownTokensAreRejected() {
        String first = store.open("reader", "hash");
        String second = store.open("reader", "hash");

        assertThat(first).isNotEqualTo(second).hasSizeGreaterThanOrEqualTo(43);
        assertThat(store.use("forged")).isEmpty();
        assertThat(store.use(null)).isEmpty();
        store.close(first);
        assertThat(store.use(first)).isEmpty();
    }

    @Test
    void sessionOnlyMatchesThePasswordItWasOpenedWith() {
        KomgaSessionStore.Session session = store.use(store.open("reader", "hash-1")).orElseThrow();

        assertThat(session.matches("hash-1")).isTrue();
        assertThat(session.matches("hash-2")).isFalse();
    }

    @Test
    void fullStoreEvictsTheLeastRecentlyUsedSession() {
        String oldest = store.open("reader", "hash");
        clock.set(NOW.plusSeconds(1));
        for (int i = 1; i < KomgaSessionStore.MAX_SESSIONS; i++) {
            store.open("reader" + i, "hash");
        }
        clock.set(NOW.plusSeconds(2));

        String newest = store.open("late", "hash");

        assertThat(store.size()).isEqualTo(KomgaSessionStore.MAX_SESSIONS);
        assertThat(store.use(oldest)).isEmpty();
        assertThat(store.use(newest)).isPresent();
    }

    // ==================== Filters ====================

    @Test
    void basicAuthenticationOpensASessionLimitedToTheKomgaApi() throws Exception {
        authenticate(user("reader", "hash"));
        MockHttpServletRequest request = request();
        request.setSecure(true);
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic xxx");
        MockHttpServletResponse response = new MockHttpServletResponse();

        KomgaSessionFilter.open(store).doFilter(request, response, new MockFilterChain());

        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .startsWith("KOMGA-SESSION=")
                .contains("Path=/komga/api", "Max-Age=604800", "HttpOnly", "Secure", "SameSite=Lax");
        String token = setCookie.substring("KOMGA-SESSION=".length(), setCookie.indexOf(';'));
        assertThat(store.use(token)).hasValueSatisfying(s -> assertThat(s.username()).isEqualTo("reader"));
    }

    @Test
    void noSessionIsOpenedWithoutBasicCredentialsOrWhenOneIsAlreadyLive() throws Exception {
        authenticate(user("reader", "hash"));
        MockHttpServletResponse noHeader = new MockHttpServletResponse();
        KomgaSessionFilter.open(store).doFilter(request(), noHeader, new MockFilterChain());
        assertThat(noHeader.getHeader(HttpHeaders.SET_COOKIE)).isNull();

        String token = store.open("reader", "hash");
        MockHttpServletRequest withLiveSession = request();
        withLiveSession.addHeader(HttpHeaders.AUTHORIZATION, "Basic xxx");
        withLiveSession.setCookies(new Cookie(KomgaSessionFilter.COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();
        KomgaSessionFilter.open(store).doFilter(withLiveSession, response, new MockFilterChain());
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();

        SecurityContextHolder.clearContext();
        MockHttpServletRequest failedBasic = request();
        failedBasic.addHeader(HttpHeaders.AUTHORIZATION, "Basic xxx");
        MockHttpServletResponse unauthenticated = new MockHttpServletResponse();
        KomgaSessionFilter.open(store).doFilter(failedBasic, unauthenticated, new MockFilterChain());
        assertThat(unauthenticated.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void liveSessionCookieAuthenticatesARequestWithoutCredentials() throws Exception {
        UserDetails reader = user("reader", "hash");
        when(userGateway.userDetails("reader")).thenReturn(reader);
        MockHttpServletRequest request = request();
        request.setCookies(new Cookie(KomgaSessionFilter.COOKIE_NAME, store.open("reader", "hash")));

        Authentication seen = runRestore(request, new MockHttpServletResponse());

        assertThat(seen).isNotNull();
        assertThat(seen.isAuthenticated()).isTrue();
        assertThat(seen.getPrincipal()).isSameAs(reader);
    }

    @Test
    void staleSessionIsClosedAndItsCookieCleared() throws Exception {
        // The password changed since the session was opened
        when(userGateway.userDetails("reader")).thenReturn(user("reader", "new-hash"));
        String token = store.open("reader", "hash");
        MockHttpServletRequest request = request();
        request.setCookies(new Cookie(KomgaSessionFilter.COOKIE_NAME, token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(runRestore(request, response)).isNull();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).startsWith("KOMGA-SESSION=;").contains("Max-Age=0");
        assertThat(store.use(token)).isEmpty();
    }

    @Test
    void removedOrDisabledAccountEndsTheSession() throws Exception {
        String removed = store.open("gone", "hash");
        String disabled = store.open("blocked", "hash");
        when(userGateway.userDetails("gone")).thenReturn(null);
        when(userGateway.userDetails("blocked")).thenReturn(User.withUsername("blocked").password("hash").disabled(true).build());

        for (String token : new String[]{removed, disabled}) {
            MockHttpServletRequest request = request();
            request.setCookies(new Cookie(KomgaSessionFilter.COOKIE_NAME, token));
            assertThat(runRestore(request, new MockHttpServletResponse())).isNull();
        }
    }

    @Test
    void basicCredentialsTakePrecedenceOverTheCookie() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic xxx");
        request.setCookies(new Cookie(KomgaSessionFilter.COOKIE_NAME, store.open("reader", "hash")));

        assertThat(runRestore(request, new MockHttpServletResponse())).isNull();
        verifyNoInteractions(userGateway);
    }

    // ==================== Helpers ====================

    private Authentication runRestore(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();
        OncePerRequestFilter filter = KomgaSessionFilter.restore(store, userGateway);
        filter.doFilter(request, response, (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication()));
        return seen.get();
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/komga/api/v1/series/1-akira");
    }

    private static UserDetails user(String username, String passwordHash) {
        return User.withUsername(username).password(passwordHash).build();
    }

    private static void authenticate(UserDetails user) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
