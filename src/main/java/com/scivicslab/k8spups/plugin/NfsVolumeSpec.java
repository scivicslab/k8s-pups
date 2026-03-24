package com.scivicslab.k8spups.plugin;

/**
 * Specification for an additional NFS volume to mount into a plugin container.
 *
 * @param server    NFS server address (e.g. "10.0.0.20")
 * @param path      NFS export path (e.g. "/Public/docusaurus-sites")
 * @param mountPath container mount path (e.g. "/output")
 * @param readOnly  whether to mount read-only
 */
public record NfsVolumeSpec(
    String server,
    String path,
    String mountPath,
    boolean readOnly
) {}
