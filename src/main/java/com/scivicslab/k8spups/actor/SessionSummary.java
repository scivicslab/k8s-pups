package com.scivicslab.k8spups.actor;

/**
 * Aggregated snapshot of all sessions, used by the dashboard status bar.
 */
public record SessionSummary(int total, int ready, int starting, int failed) {}
