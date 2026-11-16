package org.booklore.komga.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.booklore.komga.core.KomgaUserGateway;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

/**
 * Komga API sessions, like Komga's {@code KOMGA-SESSION} cookie: the Mihon tracker sends no credentials and
 * relies on the cookie set for the Komga extension, with which it shares its cookie jar.
 * <ul>
 *   <li>{@link #restore}: placed before Basic authentication. A request without {@code Authorization} header
 *       but with a live session cookie is authenticated as the session's OPDS account, reloaded on each request
 *       (a removed account, lost OPDS access or a new password end the session).</li>
 *   <li>{@link #open}: placed after Basic authentication. A request authenticated with Basic credentials and
 *       without a live session of the same account gets a new session cookie.</li>
 * </ul>
 * The cookie is limited to {@code /komga/api}, so it never reaches the rest of Grimmory. These filters are not
 * beans on purpose: they only run in the Komga security chain, not as servlet filters on every request.
 */
public final class KomgaSessionFilter {

    public static final String COOKIE_NAME = "KOMGA-SESSION";
    static final String COOKIE_PATH = "/komga/api";

    private KomgaSessionFilter() {
    }

    public static OncePerRequestFilter restore(KomgaSessionStore store, KomgaUserGateway userGateway) {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String token = cookieValue(request);
                if (token != null && request.getHeader(HttpHeaders.AUTHORIZATION) == null) {
                    UserDetails user = liveSessionUser(store, userGateway, token).orElse(null);
                    if (user != null) {
                        SecurityContext context = SecurityContextHolder.createEmptyContext();
                        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
                        SecurityContextHolder.setContext(context);
                    } else {
                        // Unknown, expired or stale: forget it; the client answers the 401 with Basic credentials
                        store.close(token);
                        response.addHeader(HttpHeaders.SET_COOKIE, cookie(request, "", 0).toString());
                    }
                }
                chain.doFilter(request, response);
            }
        };
    }

    public static OncePerRequestFilter open(KomgaSessionStore store) {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                if (request.getHeader(HttpHeaders.AUTHORIZATION) != null
                        && authentication != null && authentication.isAuthenticated()
                        && authentication.getPrincipal() instanceof UserDetails user
                        && !hasLiveSession(store, cookieValue(request), user.getUsername())) {
                    String token = store.open(user.getUsername(), user.getPassword());
                    response.addHeader(HttpHeaders.SET_COOKIE,
                            cookie(request, token, KomgaSessionStore.IDLE_TIMEOUT.toSeconds()).toString());
                }
                chain.doFilter(request, response);
            }
        };
    }

    private static Optional<UserDetails> liveSessionUser(KomgaSessionStore store, KomgaUserGateway userGateway, String token) {
        return store.use(token).flatMap(session -> {
            UserDetails user = userGateway.userDetails(session.username());
            boolean valid = user != null && user.isEnabled() && session.matches(user.getPassword());
            return valid ? Optional.of(user) : Optional.empty();
        });
    }

    private static boolean hasLiveSession(KomgaSessionStore store, String token, String username) {
        return token != null && store.use(token).map(session -> session.username().equals(username)).orElse(false);
    }

    private static String cookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    static ResponseCookie cookie(HttpServletRequest request, String value, long maxAgeSeconds) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .path(COOKIE_PATH)
                .maxAge(maxAgeSeconds)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .build();
    }
}
