package com.scivicslab.k8spups.k8s;

/**
 * POSIX account information from LDAP, used to configure
 * NFS workspace mounting and container security context.
 */
public record WorkspaceInfo(
    long uid,
    long gid,
    String homeDirectory,
    String username
) {}
