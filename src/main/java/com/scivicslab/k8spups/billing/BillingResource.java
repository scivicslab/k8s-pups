package com.scivicslab.k8spups.billing;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Read-only API for the billing system: how long the Pods of the users ran and how much storage was reserved. The billing
 * system calls it from inside the cluster, with the shared token in {@code Authorization: Bearer <token>}; the gateway answers
 * 404 to every request from outside (the route filter of {@code /api/billing}). The controller keeps no personal data and no
 * mapping from users to responsible persons: it returns every row, including those of the accounts that only the tests use,
 * and the billing system decides whom to charge.
 *
 * <p>With no token configured ({@code k8spups.billing.api-token}), the API does not exist: every request gets 404.
 */
@Path("/api/billing")
@Produces(MediaType.APPLICATION_JSON)
public class BillingResource {

    private static final Logger LOG = Logger.getLogger(BillingResource.class.getName());

    @ConfigProperty(name = "k8spups.billing.api-token")
    Optional<String> token;

    @Inject
    BillingRepository repository;

    /**
     * The Pods that were ready before {@code to} and had not ended before {@code from}, in the order of their ids.
     * {@code after_id} continues after the last id of the previous response; {@code next_after_id} in the response is that id,
     * or null when the response holds the last rows.
     */
    @GET
    @Path("/pod-usage")
    public Response podUsage(@HeaderParam("Authorization") String authorization,
                             @QueryParam("from") String from, @QueryParam("to") String to,
                             @QueryParam("after_id") Long afterId, @QueryParam("limit") String limit) {
        Response denied = denyUnlessAllowed(authorization);
        if (denied != null) return denied;
        try {
            Instant fromInstant = BillingParams.instant(from, "from");
            Instant toInstant = BillingParams.instant(to, "to");
            int max = BillingParams.limit(limit);
            BillingRepository.PodUsagePage page = repository.podUsage(fromInstant, toInstant, afterId == null ? 0 : afterId, max);
            List<BillingRepository.PodUsageRow> rows = page.rows();
            Long next = rows.size() == max ? rows.get(rows.size() - 1).id() : null;
            return Response.ok(new PodUsageResponse(page.generatedAt(), next, rows)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).type(MediaType.TEXT_PLAIN).entity(e.getMessage()).build();
        }
    }

    /** The storage reserved on each day (Japan time) from {@code from} to {@code to}, both included, at most one year. */
    @GET
    @Path("/storage-reservations/daily")
    public Response dailyReservations(@HeaderParam("Authorization") String authorization,
                                      @QueryParam("from") String from, @QueryParam("to") String to) {
        Response denied = denyUnlessAllowed(authorization);
        if (denied != null) return denied;
        try {
            LocalDate fromDay = BillingParams.day(from, "from");
            LocalDate toDay = BillingParams.day(to, "to");
            BillingParams.checkDays(fromDay, toDay);
            BillingRepository.DailyReservationPage page = repository.dailyReservations(fromDay, toDay);
            return Response.ok(new DailyReservationResponse(page.generatedAt(), page.rows())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).type(MediaType.TEXT_PLAIN).entity(e.getMessage()).build();
        }
    }

    /** Null when the request carries the token; otherwise the response to return. */
    private Response denyUnlessAllowed(String authorization) {
        if (token.isEmpty() || token.get().isBlank()) return Response.status(404).type(MediaType.TEXT_PLAIN).entity("Not found").build();
        if (!BillingToken.accepts(authorization, token.get())) {
            LOG.warning("Billing API: a request without the right token was refused");
            return Response.status(401).header("WWW-Authenticate", "Bearer").type(MediaType.TEXT_PLAIN).entity("Unauthorized").build();
        }
        return null;
    }

    /** {@code generated_at}: the time up to which the seconds of the rows still running were counted. */
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PodUsageResponse(String generatedAt, Long nextAfterId, List<BillingRepository.PodUsageRow> rows) {}

    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DailyReservationResponse(String generatedAt, List<BillingRepository.DailyReservationRow> rows) {}
}
