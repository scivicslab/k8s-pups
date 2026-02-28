package com.scivicslab.k8spups.k8s;

import java.util.Hashtable;
import java.util.Optional;
import java.util.logging.Logger;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;

/**
 * Queries LDAP for POSIX account attributes (uidNumber, gidNumber, homeDirectory).
 * Used to determine if a user has a host filesystem ($HOME) to mount via NFS.
 *
 * <p>Returns empty if the user has no posixAccount entry, so callers can
 * silently skip workspace mounting for non-POSIX users.</p>
 */
public class LdapUserInfoClient {

    private static final Logger LOG = Logger.getLogger(LdapUserInfoClient.class.getName());

    private final String ldapUrl;
    private final String baseDn;
    private final String bindDn;
    private final String bindPassword;

    public LdapUserInfoClient(String ldapUrl, String baseDn, String bindDn, String bindPassword) {
        this.ldapUrl = ldapUrl;
        this.baseDn = baseDn;
        this.bindDn = bindDn;
        this.bindPassword = bindPassword;
    }

    /**
     * Look up POSIX account info for a user by uid.
     * Returns empty if the user does not exist or has no posixAccount attributes.
     */
    public Optional<WorkspaceInfo> lookup(String username) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, ldapUrl);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, bindDn);
        env.put(Context.SECURITY_CREDENTIALS, bindPassword);

        try {
            DirContext ctx = new InitialDirContext(env);
            try {
                SearchControls controls = new SearchControls();
                controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
                controls.setReturningAttributes(new String[]{"uidNumber", "gidNumber", "homeDirectory", "uid"});

                String filter = "(&(objectClass=posixAccount)(uid=" + escapeLdapFilter(username) + "))";
                NamingEnumeration<SearchResult> results = ctx.search(baseDn, filter, controls);

                if (results.hasMore()) {
                    SearchResult result = results.next();
                    Attributes attrs = result.getAttributes();

                    Attribute uidNum = attrs.get("uidNumber");
                    Attribute gidNum = attrs.get("gidNumber");
                    Attribute homeDirAttr = attrs.get("homeDirectory");
                    Attribute uidAttr = attrs.get("uid");

                    if (uidNum != null && gidNum != null && homeDirAttr != null) {
                        long uid = Long.parseLong(uidNum.get().toString());
                        long gid = Long.parseLong(gidNum.get().toString());
                        String homeDir = homeDirAttr.get().toString();
                        String uidName = uidAttr != null ? uidAttr.get().toString() : username;

                        LOG.info("LDAP lookup for " + username + ": uid=" + uid
                            + ", gid=" + gid + ", home=" + homeDir);
                        return Optional.of(new WorkspaceInfo(uid, gid, homeDir, uidName));
                    }
                }

                LOG.info("LDAP lookup for " + username + ": no posixAccount found");
                return Optional.empty();
            } finally {
                ctx.close();
            }
        } catch (Exception e) {
            LOG.warning("LDAP lookup failed for " + username + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Escape special characters in LDAP filter values to prevent injection.
     */
    private static String escapeLdapFilter(String input) {
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            switch (c) {
                case '\\': sb.append("\\5c"); break;
                case '*':  sb.append("\\2a"); break;
                case '(':  sb.append("\\28"); break;
                case ')':  sb.append("\\29"); break;
                case '\0': sb.append("\\00"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }
}
