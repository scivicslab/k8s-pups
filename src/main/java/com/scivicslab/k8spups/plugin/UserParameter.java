package com.scivicslab.k8spups.plugin;

/**
 * Defines a user-provided parameter shown on the dashboard at session start.
 * The value entered by the user is injected as an environment variable into the Pod.
 *
 * @param envVarName  environment variable name set in the Pod (e.g. "ANTHROPIC_API_KEY")
 * @param label       label shown on the dashboard form (e.g. "API Key")
 * @param placeholder placeholder text for the input field
 * @param secret      true to mask the input (password field)
 * @param required    true if the parameter must be provided before starting
 */
public record UserParameter(
    String envVarName,
    String label,
    String placeholder,
    boolean secret,
    boolean required
) {}
