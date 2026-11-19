package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.dataset.DatasetRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

/**
 * What a tool session may send to the browser. A user reads restricted data in a tool and could save it by downloading it
 * through the tool's own screen; these limits apply to everything the session proxy returns, whichever tool made it:
 * <ul>
 *   <li>a response that asks to be saved as a file ({@code Content-Disposition: attachment}) is refused;</li>
 *   <li>one response may not be larger than {@code k8spups.egress.max-response-bytes} (an event stream, which has no end, is
 *       not cut by this one);</li>
 *   <li>everything sent to the browser in one session (HTTP bodies and WebSocket messages from the tool) may not exceed
 *       {@code k8spups.egress.max-session-bytes}. A session lives at most 24 hours (K8SPUPS_MAX_LIFETIME_MINUTES), so the
 *       total of a session is the total of a day.</li>
 * </ul>
 * A limit of 0 turns that limit off. The counts are in memory: a restart of the controller starts them from 0 again.
 * Only {@code /session/<id>/...} is counted. The controller's own pages and the Airlock downloads do not pass through here.
 */
@ApplicationScoped
public class SessionEgress {

    private static final Logger LOG = Logger.getLogger(SessionEgress.class.getName());

    @ConfigProperty(name = "k8spups.egress.max-response-bytes", defaultValue = "10485760")
    long maxResponseBytes;

    @ConfigProperty(name = "k8spups.egress.max-session-bytes", defaultValue = "209715200")
    long maxSessionBytes;

    @ConfigProperty(name = "k8spups.egress.reject-attachment", defaultValue = "true")
    boolean rejectAttachment;

    @Inject
    DatasetRepository audit;

    private final ConcurrentHashMap<String, LongAdder> sent = new ConcurrentHashMap<>();

    /** The bytes sent to the browser so far in the session: add what is sent to it. */
    public LongAdder counter(String sessionId) {
        return sent.computeIfAbsent(sessionId, id -> new LongAdder());
    }

    /** @return true when the session has already sent its limit */
    public boolean overSessionLimit(String sessionId) {
        return exceeds(maxSessionBytes, counter(sessionId).sum());
    }

    /**
     * Judges a response from its headers, before any of its body is sent.
     *
     * @param contentLength the Content-Length, or -1 when the response has none
     * @return why the response is refused, or null when it may be sent
     */
    public String refusalFor(String sessionId, String contentDisposition, long contentLength, boolean eventStream) {
        return refusal(rejectAttachment, maxResponseBytes, maxSessionBytes, counter(sessionId).sum(),
            contentDisposition, contentLength, eventStream);
    }

    /** @return true when one response that has sent {@code responseBytes} has gone over its limit */
    public boolean overResponseLimit(long responseBytes, boolean eventStream) {
        return !eventStream && exceeds(maxResponseBytes, responseBytes);
    }

    /** Records a refusal in the log and in the audit trail; a failure of the trail does not change the refusal. */
    public void recordRefusal(String user, String sessionId, String tool, String reason) {
        LOG.warning("Session egress refused: user=" + user + " sessionId=" + sessionId + " tool=" + tool + " reason=" + reason);
        try {
            audit.addAudit("egress.refused", user, user, sessionId, null, tool, null, reason);
        } catch (RuntimeException e) {
            LOG.warning("The refusal could not be written to the audit trail: " + e.getMessage());
        }
    }

    // The decisions are static and take their limits as arguments, so that they can be tested without a running server.

    static String refusal(boolean rejectAttachment, long maxResponse, long maxSession, long sentSoFar,
                          String contentDisposition, long contentLength, boolean eventStream) {
        if (rejectAttachment && isAttachment(contentDisposition)) {
            return "the response is a file download (Content-Disposition: attachment)";
        }
        if (!eventStream && exceeds(maxResponse, contentLength)) {
            return "the response is " + contentLength + " bytes, over the limit of " + maxResponse + " bytes";
        }
        if (exceeds(maxSession, sentSoFar)) {
            return "the session has already sent " + sentSoFar + " bytes, its limit is " + maxSession + " bytes";
        }
        return null;
    }

    static boolean isAttachment(String contentDisposition) {
        return contentDisposition != null && contentDisposition.trim().toLowerCase(Locale.ROOT).startsWith("attachment");
    }

    /** A limit of 0 or less is off; a size of -1 (unknown) never exceeds it. */
    static boolean exceeds(long limit, long size) {
        return limit > 0 && size > limit;
    }
}
