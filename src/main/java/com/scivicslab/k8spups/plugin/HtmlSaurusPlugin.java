package com.scivicslab.k8spups.plugin;

import java.util.List;
import java.util.Map;

/**
 * Tool plugin for the document portal of html-saurus: every Docusaurus project found under the user's {@code ~/works}
 * (their own storage and the storage of their groups), with full-text and semantic search, in the browser.
 *
 * <p>Access is decided by the controller (login and ownership of the session), so the portal has no password of its own.
 * The gateway removes the session path prefix before it forwards a request; the portal is told the prefix in
 * {@code HTML_SAURUS_BASE_PATH} (the entrypoint takes it from {@code PUPS_SESSION_PATH}) and writes every URL of its pages
 * below it, so the controller does not rewrite the responses.
 *
 * <p>The sources of the projects are copied at the start of the Pod and the copy is served, because the portal builds
 * into the project directory and the storage of a group is often read-only; start the tool again to see newer documents.
 */
public class HtmlSaurusPlugin implements ToolPlugin {

    /** The directory the portal scans for Docusaurus projects, and where the user's storage is mounted. */
    static final String WORKSPACE = "/home/devteam/works";

    /** Where the copy of the projects is built and served; a directory of the Pod that every user can write. */
    static final String PORTAL_DIR = "/home/devteam/portal";

    @Override
    public String name() {
        return "html-saurus";
    }

    @Override
    public String displayName() {
        return "Documents";
    }

    @Override
    public String description() {
        return "Read the documents on your storage and your groups' storage, in the browser, and search them by keyword or by meaning.";
    }

    @Override
    public String icon() {
        return "icons/docusaurus.png";
    }

    @Override
    public String containerImage() {
        return "${REGISTRY}/html-saurus:2.7.0-2611081615";
    }

    @Override
    public int containerPort() {
        return 8080;
    }

    @Override
    public ConnectionType connectionType() {
        return ConnectionType.HTTP;
    }

    @Override
    public Map<String, String> environmentVariables() {
        return Map.of("HOME", "/home/devteam");
    }

    /** The portal answers once its projects are built; the index page is its first route. */
    @Override
    public String readinessProbePath() {
        return "/";
    }

    @Override
    public int readinessProbeInitialDelay() {
        return 10;
    }

    @Override
    public Map<String, String> resourceRequests() {
        return Map.of("cpu", "250m", "memory", "1Gi");
    }

    @Override
    public Map<String, String> resourceLimits() {
        return Map.of("cpu", "2", "memory", "4Gi");
    }

    /** Name of the variable that carries the folder patterns to the image; the entrypoint reads it. */
    static final String PROJECTS_ENV = "PORTAL_PROJECTS";

    /**
     * Which folders of the storage are Docusaurus projects. Left empty, the image looks for projects near the top of
     * {@code ~/works} (at most two folders down); with patterns, it reads only the folders that match.
     */
    @Override
    public List<UserParameter> userParameters() {
        return List.of(new UserParameter(PROJECTS_ENV, "Document folders, e.g. ~/works/doc_*", "~/works/doc_*", false, false));
    }

    @Override
    public String checkUserParameter(String envVarName, String value) {
        return PROJECTS_ENV.equals(envVarName) ? DocumentFolderPatterns.check(value) : null;
    }

    /** The copy of the projects, with what the portal builds from it; it lasts as long as the session. */
    @Override
    public List<String> writablePaths() {
        return List.of(PORTAL_DIR);
    }

    @Override
    public String userDataMountPath() {
        return WORKSPACE;
    }

    @Override
    public String extraMountsUnder() {
        // The entrypoint scans this directory: the group storage and the datasets are found in its file tree.
        return WORKSPACE;
    }

    @Override
    public Long runAsUser() {
        return 1000L;
    }

    @Override
    public boolean runAsNonRoot() {
        return true;
    }
}
