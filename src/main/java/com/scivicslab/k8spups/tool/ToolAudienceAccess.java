package com.scivicslab.k8spups.tool;

import com.scivicslab.k8spups.plugin.ToolAudience;

import java.util.Collection;
import java.util.Set;

/**
 * Who may be offered a tool and start it. A developer is named the way an administrator is: by user id
 * ({@code k8spups.developer-users}) or by a Keycloak role ({@code k8spups.developer-roles}); see
 * {@link SharedServiceAccess} for the administrators. Pure logic, so the rule is unit-tested without a token or a
 * cluster. The check is applied where a tool is started, not only in the page: hiding a card does not stop a request.
 */
public final class ToolAudienceAccess {

    private ToolAudienceAccess() {
    }

    /**
     * True when {@code userId} is one of {@code developerUsers} or {@code roles} contains one of
     * {@code developerRoles}. Both lists are comma-separated; blanks are ignored.
     */
    public static boolean isDeveloper(String userId, Collection<String> roles, String developerUsers,
                                      String developerRoles) {
        // The same rule as for an administrator, with the lists of developers.
        return SharedServiceAccess.isAdmin(userId, roles, developerUsers, developerRoles);
    }

    /** The names in a comma-separated list; blanks are ignored. */
    public static Set<String> names(String csv) {
        return new java.util.LinkedHashSet<>(SharedServiceAccess.split(csv));
    }

    /** Whether a user of this standing may be offered, and may start, a tool of this audience. */
    public static boolean allowed(ToolAudience audience, boolean administrator, boolean developer) {
        return switch (audience) {
            case EVERYONE -> true;
            case DEVELOPERS -> administrator || developer;
            case ADMINISTRATORS -> administrator;
        };
    }

    /**
     * The audience of a tool: the one the tool declares, narrowed to developers when the deployment lists the tool in
     * {@code k8spups.developer-tools}. A deployment can restrict a tool further; it cannot open one the tool restricts.
     *
     * @param declared       the audience the tool declares in its code
     * @param toolName       name of the tool
     * @param developerTools names of the tools the deployment keeps for developers
     */
    public static ToolAudience effective(ToolAudience declared, String toolName, Set<String> developerTools) {
        return developerTools.contains(toolName) ? declared.narrower(ToolAudience.DEVELOPERS) : declared;
    }
}
