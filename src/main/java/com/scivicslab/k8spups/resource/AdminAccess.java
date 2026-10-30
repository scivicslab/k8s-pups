package com.scivicslab.k8spups.resource;

import com.scivicslab.k8spups.actor.K8sPupsActorSystem;
import com.scivicslab.k8spups.tool.JwtRoleExtractor;

import io.quarkus.oidc.IdToken;
import io.quarkus.security.identity.SecurityIdentity;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Who is calling, and whether the caller may use the administrator pages. Every administrator page asks this one
 * place, so the rule is written once.
 */
@RequestScoped
public class AdminAccess {

    private static final Logger LOG = Logger.getLogger(AdminAccess.class.getName());

    @Inject
    K8sPupsActorSystem actorSystem;

    @Inject
    @IdToken
    JsonWebToken idToken;

    @Inject
    SecurityIdentity identity;

    /** The user name of the caller (the login name of the identity token), or null when there is none. */
    public String user() {
        try {
            if (idToken != null) {
                String username = idToken.getClaim("preferred_username");
                if (username != null) return username;
            }
        } catch (Exception e) {
            // Fall back to the security identity.
        }
        return identity != null && !identity.isAnonymous() ? identity.getPrincipal().getName() : null;
    }

    /** The roles of the caller, from the realm and the client roles of the identity token. */
    public List<String> roles() {
        try {
            if (idToken == null) return List.of();
            Map<String, Object> realmAccess = idToken.getClaim("realm_access");
            Map<String, Object> resourceAccess = idToken.getClaim("resource_access");
            return JwtRoleExtractor.extractRoles(realmAccess, resourceAccess);
        } catch (Exception e) {
            return List.of();
        }
    }

    public boolean isAdmin() {
        return actorSystem.isAdmin(user(), roles());
    }

    /**
     * Null when the caller is an administrator; otherwise the 403 response to return. {@code area} names the page in the log.
     */
    public Response denyUnlessAdmin(String area) {
        if (isAdmin()) return null;
        LOG.warning(area + " refused for user " + user());
        return Response.status(Response.Status.FORBIDDEN).entity("Administrators only").build();
    }
}
