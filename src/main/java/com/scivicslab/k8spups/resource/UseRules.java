package com.scivicslab.k8spups.resource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What of the traffic between the browser and a tool is use of the session, and so keeps it from the idle limit.
 *
 * <p>An open page of many tools talks to its tool by itself: Jupyter asks for its kernels and saves its workspace every
 * ten seconds, Galaxy asks for the current history every few seconds, the code editor and the remote desktop keep their
 * WebSocket alive. Counted as use, a tab left open would keep the session for ever. The rule is therefore: everything is
 * use, except the background traffic a tool is known to send (measured on an untouched page of each tool), and a page
 * load is always use. A tool with no rule here counts everything, so no session is stopped for a rule that is missing.
 * Pure logic.
 */
final class UseRules {

    private UseRules() {
    }

    /** One request the untouched page sends: method and a pattern of the path after the session prefix. */
    private record Background(String method, Pattern path) {
        boolean matches(String m, String subPath) {
            return method.equals(m) && path.matcher(subPath).matches();
        }
    }

    private static Background get(String regex) {
        return new Background("GET", Pattern.compile(regex));
    }

    /** JupyterLab polls these every ten seconds and saves its workspace state as often. */
    private static final List<Background> JUPYTER = List.of(
        get("api/(terminals|kernels|sessions|kernelspecs|me|status)/?"),
        get("api/contents(/.*)?"),
        get("lab/api/workspaces(/.*)?"),
        new Background("PUT", Pattern.compile("lab/api/workspaces/.*")));

    private static final List<Background> GALAXY = List.of(get("history/current_history_json"));

    /** The AI agent's page polls the state of its actors every three seconds. */
    private static final List<Background> AI_AGENT = List.of(get("api/actors/?"));

    /** The Guacamole instructions that a person makes; nop, sync, ack and ping are the protocol's own. */
    private static final Set<String> GUACAMOLE_USER_OPCODES = Set.of("key", "mouse", "touch", "clipboard", "size");

    /** The desktop tools, whose screen is Guacamole over a WebSocket. */
    private static final Set<String> DESKTOPS = Set.of("guacamole", "fedora-desktop", "debian-desktop", "kali-desktop");

    private static List<Background> backgroundOf(String tool) {
        if (tool == null) return List.of();
        return switch (tool) {
            case "jupyter-lab", "jupyter-ai" -> JUPYTER;
            case "galaxy" -> GALAXY;
            case "chat-ui-with-audit-trail-local-llm" -> AI_AGENT;
            default -> List.of();
        };
    }

    /**
     * @param tool          the session's tool plugin name; null when unknown
     * @param subPath       the path after the session prefix, without its leading slash
     * @param secFetchMode  the request's Sec-Fetch-Mode header; {@code navigate} is a page the user opened
     */
    static boolean httpIsUse(String tool, String method, String subPath, String secFetchMode) {
        if ("navigate".equalsIgnoreCase(secFetchMode)) return true;
        String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
        String path = subPath == null ? "" : subPath.replaceFirst("^/+", "");
        for (Background b : backgroundOf(tool)) {
            if (b.matches(m, path)) return false;
        }
        return true;
    }

    /**
     * A message the browser sent to the tool over a WebSocket. Only the first frame of a message is judged.
     *
     * @param text    the text of a text frame; null for a binary frame
     * @param binary  the bytes of a binary frame; null for a text frame
     */
    static boolean webSocketIsUse(String tool, String text, byte[] binary) {
        if (tool == null) return true;
        if (DESKTOPS.contains(tool)) {
            return text == null || GUACAMOLE_USER_OPCODES.contains(guacamoleOpcode(text));
        }
        if ("code-server".equals(tool)) {
            return binary == null || vsCodeIsUse(binary);
        }
        return true;
    }

    /** The opcode of a Guacamole instruction, {@code <length>.<opcode>,...;}; an empty opcode ({@code 0.,}) is internal. */
    static String guacamoleOpcode(String instruction) {
        int dot = instruction.indexOf('.');
        if (dot < 0) return "";
        int len;
        try {
            len = Integer.parseInt(instruction.substring(0, dot));
        } catch (NumberFormatException e) {
            return "";
        }
        int end = dot + 1 + len;
        return end <= instruction.length() ? instruction.substring(dot + 1, end) : "";
    }

    /**
     * The VS Code remote protocol: a 13-byte header whose first byte is the message type. Only regular messages (type 1)
     * carry what the user does; keep-alives (9) and acknowledgements (3) are the protocol's own, and so is the regular
     * telemetry ping the page sends every fifteen seconds.
     */
    static boolean vsCodeIsUse(byte[] message) {
        if (message.length < 13 || message[0] != 1) return false;
        String body = new String(message, 13, message.length - 13, StandardCharsets.ISO_8859_1);
        return !(message.length <= 48 && body.contains("telemetry") && body.contains("ping"));
    }
}
