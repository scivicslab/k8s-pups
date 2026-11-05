package com.scivicslab.k8spups.actor;

/**
 * How long a session may stay in each state before the controller stops it. A value of 0 or less turns that limit off.
 *
 * @param idleMinutes     a ready session that nobody used for this long
 * @param lifetimeMinutes a ready session that has run this long, used or not
 * @param startMinutes    a session that did not become ready in this time (its Pod cannot be scheduled, its image
 *                        pulled or its storage mounted, or its tool never answers)
 * @param failedMinutes   a failed session, this long after the failure was seen, so that the user sees it first
 */
public record SessionTimeouts(long idleMinutes, long lifetimeMinutes, long startMinutes, long failedMinutes) {
}
