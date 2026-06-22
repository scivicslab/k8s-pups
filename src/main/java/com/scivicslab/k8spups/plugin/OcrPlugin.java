package com.scivicslab.k8spups.plugin;

import java.util.List;
import java.util.Map;

/**
 * Batch plugin that converts PDF files in the user's PVC to structured Markdown
 * using YomiToku OCR. Runs as a one-shot Pod (batchMode=true) and auto-cleans
 * up when the conversion completes.
 *
 * Workflow: user uploads PDF via File Browser → launches this job on the same
 * storage → downloads the generated .md file via File Browser.
 */
public class OcrPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "pdf-ocr";
    }

    @Override
    public String displayName() {
        return "PDF → Markdown OCR";
    }

    @Override
    public String description() {
        return "Convert all PDF files in your storage to structured Markdown using YomiToku OCR. Runs as a batch job and exits when complete.";
    }

    @Override
    public String icon() {
        return "icons/storage.png";
    }

    @Override
    public String containerImage() {
        return "192.168.5.23:32000/yomitoku-ocr-batch:0.1.0-2606151703";
    }

    @Override
    public int containerPort() {
        return 8080;
    }

    @Override
    public ConnectionType connectionType() {
        return ConnectionType.VNC;
    }

    @Override
    public boolean batchMode() {
        return true;
    }

    @Override
    public boolean singleInstance() {
        return false;
    }

    @Override
    public String userDataMountPath() {
        return "/data";
    }

    @Override
    public boolean workspaceEnabled() {
        return false;
    }

    @Override
    public boolean readOnlyRootFilesystem() {
        return false;
    }

    @Override
    public Long runAsUser() {
        return null;
    }

    @Override
    public boolean runAsNonRoot() {
        return false;
    }

    @Override
    public Map<String, String> resourceRequests() {
        return Map.of("cpu", "2", "memory", "4Gi");
    }

    @Override
    public Map<String, String> resourceLimits() {
        return Map.of("cpu", "4", "memory", "8Gi");
    }

    @Override
    public List<ResourceProfile> resourceProfiles() {
        return List.of(new ResourceProfile("default", "Default",
            resourceRequests(), resourceLimits()));
    }

    @Override
    public Map<String, String> environmentVariables() {
        return Map.of(
            "YOMITOKU_DEVICE", "cpu",
            "HF_HOME", "/data/.yomitoku-cache"
        );
    }
}
