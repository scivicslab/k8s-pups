package com.scivicslab.k8spups.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class GroupShrinkTest {

    /** volumeAttributes in the form the exa CSI driver writes for a group PV (group demo); placeholder values. */
    static final Map<String, String> DEMO = Map.of(
        "projectId", "12345",
        "exaFS", "mds0@o2ib0:mds1@o2ib0:mds2@o2ib0:mds3@o2ib0:/lustre0",
        "mountPoint", "/lustre0_k8s",
        "bindMount", "true");

    @Test
    void quotaIsReadFromProjectIdAndExaFs() {
        var q = GroupShrink.LustreQuota.of(DEMO).orElseThrow();
        assertEquals("12345", q.projectId());
        assertEquals("/lustre0", q.filesystem());
    }

    @Test
    void noQuotaWithoutBothAttributesOrWithShellSyntax() {
        assertTrue(GroupShrink.LustreQuota.of(null).isEmpty());
        assertTrue(GroupShrink.LustreQuota.of(Map.of()).isEmpty());
        assertTrue(GroupShrink.LustreQuota.of(Map.of("projectId", "12345")).isEmpty());          // nfs.csi.k8s.io PV
        assertTrue(GroupShrink.LustreQuota.of(Map.of("exaFS", "a@o2ib0:/lustre0")).isEmpty());
        Map<String, String> bad = new HashMap<>(DEMO);
        bad.put("projectId", "12345; rm -rf /");
        assertTrue(GroupShrink.LustreQuota.of(bad).isEmpty());
        bad = new HashMap<>(DEMO);
        bad.put("exaFS", "a@o2ib0:/lustre0 $(id)");
        assertTrue(GroupShrink.LustreQuota.of(bad).isEmpty());
        bad.put("exaFS", "a@o2ib0:lustre0");                                                   // not an absolute path
        assertTrue(GroupShrink.LustreQuota.of(bad).isEmpty());
    }

    @Test
    void commandsUseKibLikeLfsQuotaPrints() {
        var q = GroupShrink.LustreQuota.of(DEMO).orElseThrow();
        var c = GroupShrink.commands(q, "100Gi", "50Gi");
        assertEquals("lfs quota -p 12345 /lustre0", c.check());
        assertEquals("lfs setquota -p 12345 -B 52428800 /lustre0", c.lower());
        assertEquals("lfs setquota -p 12345 -B 104857600 /lustre0", c.undo());   // 100Gi in KiB, the blimit lfs quota shows for a 100Gi group
        assertEquals(52428800L, c.newLimitKib());
        assertEquals(2L * 1024 * 1024 * 1024, GroupShrink.kibibytes("2Ti"));
    }

    @Test
    void prepareNeedsAnActiveGroupAndASmallerWholeSize() {
        assertNull(GroupShrink.refusePrepare(true, null, "100Gi", "50Gi"));
        assertNull(GroupShrink.refusePrepare(true, null, "1Ti", "1000Gi"));
        assertEquals("The storage already has this size", GroupShrink.refusePrepare(true, null, "1Ti", "1024Gi"));
        assertTrue(GroupShrink.refusePrepare(true, null, "100Gi", "200Gi").contains("Enlarge"));
        assertTrue(GroupShrink.refusePrepare(true, null, "100Gi", "50G").startsWith("Size must be"));
        assertTrue(GroupShrink.refusePrepare(true, null, "100Gi", "").startsWith("Size must be"));
        assertTrue(GroupShrink.refusePrepare(false, null, "100Gi", "50Gi").contains("taken away"));
    }

    @Test
    void aSecondPrepareWhilePendingIsRefused() {
        assertEquals("A shrink to 50Gi is already pending; continue or cancel it first",
            GroupShrink.refusePrepare(false, "50Gi", "100Gi", "40Gi"));
    }

    @Test
    void continueAndCancelNeedAPendingShrink() {
        assertNull(GroupShrink.refuseContinue(false, "50Gi"));
        assertNotNull(GroupShrink.refuseContinue(false, null));   // taken away, nothing pending
        assertNotNull(GroupShrink.refuseContinue(true, null));    // active
    }

    @Test
    void ordinaryRestoreIsRefusedWhileAShrinkIsPending() {
        assertNull(GroupShrink.refuseRestore(null));
        assertTrue(GroupShrink.refuseRestore("50Gi").startsWith("A shrink to 50Gi is pending"));
    }
}
