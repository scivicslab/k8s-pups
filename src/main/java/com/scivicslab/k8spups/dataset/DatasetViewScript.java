package com.scivicslab.k8spups.dataset;

import java.util.Collection;
import java.util.TreeSet;

/**
 * Builds the shell script that makes one user's view directory contain hardlinks to exactly the
 * files the user is entitled to. The script runs in a short-lived Pod that mounts the dataset
 * base path read-write; the user's own Pod mounts only the view directory, read-only.
 */
public final class DatasetViewScript {

    private DatasetViewScript() {}

    /**
     * @param originalDir directory name of the original files, relative to the mounted base; empty when the original
     *                    files are the top level of the mounted base (the dataset root is a PVC)
     * @param viewDir     directory name of the views, relative to the mounted base
     * @param user        user name (one directory under the view directory)
     * @param paths       relative paths of the entitled files, relative to {@code originalDir}
     */
    public static String build(String originalDir, String viewDir, String user, Collection<String> paths) {
        if (!originalDir.isEmpty()) DatasetPath.requireRelativePath(originalDir);
        DatasetPath.requireRelativePath(viewDir);
        DatasetPath.requireUserName(user);
        TreeSet<String> sorted = new TreeSet<>();
        for (String p : paths) sorted.add(DatasetPath.requireRelativePath(p));

        String view = "$B/" + viewDir + "/" + user;
        StringBuilder sb = new StringBuilder();
        sb.append("set -e\n");
        sb.append("B=/mnt/nfs-base\n");
        sb.append("V=").append(view).append("\n");
        sb.append("O=$B").append(originalDir.isEmpty() ? "" : "/" + originalDir).append("\n");
        sb.append("mkdir -p $V\n");
        // The view is updated in place, not rebuilt: a running tool keeps reading the files that
        // stay entitled while links are added and removed around them.
        sb.append("cat > /tmp/wanted.raw <<'EOF_WANTED'\n");
        for (String p : sorted) sb.append(p).append("\n");
        sb.append("EOF_WANTED\n");
        sb.append("sort /tmp/wanted.raw > /tmp/wanted\n");
        sb.append("while read -r p; do\n");
        sb.append("  [ -n \"$p\" ] || continue\n");
        sb.append("  [ -f \"$O/$p\" ] || { echo \"MISSING $p\"; continue; }\n");
        sb.append("  mkdir -p \"$V/$(dirname \"$p\")\"\n");
        sb.append("  [ \"$(stat -c %i \"$O/$p\")\" = \"$(stat -c %i \"$V/$p\" 2>/dev/null)\" ] || ln -f \"$O/$p\" \"$V/$p\"\n");
        // Each directory of the view gets the owner, group and mode of the original directory. A directory that is
        // root-owned 0755 makes Lustre refuse a read that the group of the file allows (a007); best effort, because an
        // NFS export may squash root and refuse chown.
        sb.append("  acc=\"\"\n");
        sb.append("  for part in $(dirname \"$p\" | tr '/' ' '); do\n");
        sb.append("    [ \"$part\" = . ] && continue\n");
        sb.append("    acc=\"$acc${acc:+/}$part\"\n");
        sb.append("    chown \"$(stat -c %u:%g \"$O/$acc\")\" \"$V/$acc\" 2>/dev/null || true\n");
        sb.append("    chmod \"$(stat -c %a \"$O/$acc\")\" \"$V/$acc\" 2>/dev/null || true\n");
        sb.append("  done\n");
        sb.append("done < /tmp/wanted\n");
        // The directory of the view itself (the one a Pod mounts) is like the first dataset directory in it.
        sb.append("first=$(sed -n 1p /tmp/wanted | cut -d/ -f1)\n");
        sb.append("if [ -n \"$first\" ] && [ -d \"$O/$first\" ]; then\n");
        sb.append("  chown \"$(stat -c %u:%g \"$O/$first\")\" \"$V\" 2>/dev/null || true\n");
        sb.append("  chmod \"$(stat -c %a \"$O/$first\")\" \"$V\" 2>/dev/null || true\n");
        sb.append("fi\n");
        // Links of files that are no longer entitled are removed, then directories left empty.
        sb.append("(cd $V && find . -type f | sed 's|^\\./||' | sort) > /tmp/have\n");
        sb.append("comm -13 /tmp/wanted /tmp/have | while read -r p; do rm -f \"$V/$p\"; done\n");
        sb.append("find $V -mindepth 1 -type d -empty -delete\n");
        // No chown: a hardlink is the original file, so changing its owner would change the original. The view
        // directories stay root-owned and world-searchable (0755); who may read a file is set on the original.
        sb.append("echo 'View ready: ").append(sorted.size()).append(" entitled files'\n");
        return sb.toString();
    }
}
