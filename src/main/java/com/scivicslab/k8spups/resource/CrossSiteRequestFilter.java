package com.scivicslab.k8spups.resource;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Refuses a request that changes state (POST, PUT, PATCH, DELETE) when the browser reports that another site sent it.
 * A browser attaches its cookies to such a request whatever page caused it, so without this check a page of another
 * site could make a logged-in administrator's browser submit any form of the administrator screens, or call the
 * session proxy, with that administrator's rights.
 *
 * <p>The decision is made in this order, and the first header present settles it:</p>
 * <ol>
 *   <li>{@code Sec-Fetch-Site}, which the browser sets itself and a page cannot change: {@code same-origin} (a page of
 *       this application) and {@code none} (the user typed the address) pass; {@code same-site} and {@code cross-site}
 *       are refused.</li>
 *   <li>{@code Origin}, when the browser is too old to send {@code Sec-Fetch-Site}: it must name the host the request
 *       was sent to (the same comparison as the WebSocket proxy, {@link SessionWebSocketProxy.OriginCheck}).</li>
 *   <li>Neither header: the sender is not a browser (a Pod calling {@code /api/sub-tool}, a script), which has no cookie of
 *       the user to misuse, so the request passes.</li>
 * </ol>
 *
 * <p>One filter covers every endpoint, including the JSON endpoints and the session proxy that forwards a POST to a tool.
 * A token in each form would cover only the forms of this application.</p>
 */
public class CrossSiteRequestFilter {

    private static final Logger LOG = Logger.getLogger(CrossSiteRequestFilter.class.getName());

    private static final Set<String> METHODS_THAT_READ = Set.of("GET", "HEAD", "OPTIONS");

    @ServerRequestFilter(preMatching = true)
    public Optional<Response> refuseCrossSite(ContainerRequestContext request) {
        String secFetchSite = request.getHeaderString("Sec-Fetch-Site");
        String origin = request.getHeaderString("Origin");
        String host = request.getHeaderString("Host");
        String forwardedHost = request.getHeaderString("X-Forwarded-Host");
        if (allowed(request.getMethod(), secFetchSite, origin, host, forwardedHost)) {
            return Optional.empty();
        }
        LOG.warning("Cross-site request refused: " + request.getMethod() + " " + request.getUriInfo().getPath()
            + " Sec-Fetch-Site=" + secFetchSite + " Origin=" + origin + " Host=" + host);
        return Optional.of(Response.status(Response.Status.FORBIDDEN)
            .type(MediaType.TEXT_PLAIN)
            .entity("Cross-site request refused")
            .build());
    }

    /** @return true when a request with these methods and headers may be handled */
    static boolean allowed(String method, String secFetchSite, String origin, String host, String forwardedHost) {
        if (method == null || METHODS_THAT_READ.contains(method.toUpperCase(Locale.ROOT))) {
            return true;
        }
        if (secFetchSite != null && !secFetchSite.isBlank()) {
            String site = secFetchSite.trim().toLowerCase(Locale.ROOT);
            return site.equals("same-origin") || site.equals("none");
        }
        if (origin != null && !origin.isBlank()) {
            return SessionWebSocketProxy.OriginCheck.allowed(origin, host, forwardedHost);
        }
        return true;
    }
}
