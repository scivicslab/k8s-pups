package com.scivicslab.k8spups.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * keep_model.py of the Jupyter AI image: the chat model that the user chose earlier is replaced by the default only when
 * the OpenAI-compatible server no longer offers it. The script runs here against a server of the test and a config.json in
 * a temporary directory.
 */
class JupyterAiKeepModelTest {

    private static final Path SCRIPT = Path.of("docker/jupyter-ai/keep_model.py").toAbsolutePath();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT = "qwen3.8-flash-next";

    @TempDir
    Path home;

    private HttpServer server;
    private String modelsAnswer = "{\"object\":\"list\",\"data\":[{\"id\":\"" + DEFAULT + "\"},{\"id\":\"other-model\"}]}";
    private int status = 200;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", ex -> {
            byte[] body = modelsAnswer.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private Path writeConfig(String json) throws Exception {
        Path config = home.resolve("jupyter_ai/config.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, json);
        return config;
    }

    private int run(Path config, String base, String model) throws Exception {
        var pb = new ProcessBuilder("python3", "-I", SCRIPT.toString(), config.toString());
        pb.environment().put("JUPYTER_AI_BASE_URL", base);
        pb.environment().put("JUPYTER_AI_MODEL", model);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        lastOutput = out;
        return rc;
    }

    private String lastOutput = "";

    private JsonNode read(Path config) throws Exception {
        return JSON.readTree(Files.readString(config));
    }

    @Test
    void aModelTheServerNoLongerOffersIsReplacedByTheDefaultAndNothingElseChanges() throws Exception {
        Path config = writeConfig("{\"model_provider_id\":\"openrouter:google/gemma-4-26B-A4B-it\",\"embeddings_provider_id\":null,"
            + "\"send_with_shift_enter\":true,\"fields\":{\"openrouter:google/gemma-4-26B-A4B-it\":{\"openai_api_base\":\"http://x/v1\"}},"
            + "\"api_keys\":{\"OPENROUTER_API_KEY\":\"not-needed\"},\"completions_model_provider_id\":null}");
        assertEquals(0, run(config, baseUrl(), DEFAULT), lastOutput);
        JsonNode after = read(config);
        assertEquals("openrouter:" + DEFAULT, after.get("model_provider_id").asText());
        assertTrue(after.get("send_with_shift_enter").asBoolean());
        assertEquals("not-needed", after.get("api_keys").get("OPENROUTER_API_KEY").asText());
        assertTrue(after.get("fields").has("openrouter:google/gemma-4-26B-A4B-it"));
        assertTrue(after.get("embeddings_provider_id").isNull());
        assertTrue(lastOutput.contains("not offered by the server any more"), lastOutput);
    }

    @Test
    void aModelTheServerStillOffersIsKept() throws Exception {
        String before = "{\"model_provider_id\":\"openrouter:other-model\",\"send_with_shift_enter\":false}";
        Path config = writeConfig(before);
        assertEquals(0, run(config, baseUrl(), DEFAULT), lastOutput);
        assertEquals(before, Files.readString(config), "the file is not even rewritten");
    }

    @Test
    void whenTheServerCannotBeAskedTheChoiceStays() throws Exception {
        String before = "{\"model_provider_id\":\"openrouter:gone\"}";
        Path config = writeConfig(before);
        server.stop(0);
        assertEquals(0, run(config, baseUrl(), DEFAULT), lastOutput);
        assertEquals(before, Files.readString(config));
        modelsAnswer = "not json";
        startServer();
        assertEquals(0, run(config, baseUrl(), DEFAULT), lastOutput);
        assertEquals(before, Files.readString(config));
        modelsAnswer = "{\"data\":[]}";
        assertEquals(0, run(config, baseUrl(), DEFAULT), lastOutput);
        assertEquals(before, Files.readString(config), "an empty list says nothing about the model");
        status = 500;
        assertEquals(0, run(config, baseUrl(), DEFAULT), lastOutput);
        assertEquals(before, Files.readString(config));
    }

    @Test
    void aModelOfAnotherProviderIsNotTheServersToJudge() throws Exception {
        String before = "{\"model_provider_id\":\"anthropic:claude-x\"}";
        Path config = writeConfig(before);
        assertEquals(0, run(config, baseUrl(), DEFAULT), lastOutput);
        assertEquals(before, Files.readString(config));
    }

    @Test
    void noConfigNoDefaultOrAnUnreadableFileAreLeftAlone() throws Exception {
        Path absent = home.resolve("jupyter_ai/config.json");
        assertEquals(0, run(absent, baseUrl(), DEFAULT), lastOutput);
        assertFalse(Files.exists(absent));
        Path config = writeConfig("{\"model_provider_id\":\"openrouter:gone\"}");
        assertEquals(0, run(config, baseUrl(), ""), lastOutput);
        assertTrue(Files.readString(config).contains("gone"), "without a default there is nothing to replace it with");
        assertEquals(0, run(config, "", DEFAULT), lastOutput);
        assertTrue(Files.readString(config).contains("gone"));
        Path broken = writeConfig("{not json");
        assertEquals(0, run(broken, baseUrl(), DEFAULT), lastOutput);
        assertEquals("{not json", Files.readString(broken));
    }

    @Test
    void theDefaultLocationIsTheJupyterDataDirectoryOfTheUser() throws Exception {
        Path data = home.resolve("data");
        Path config = data.resolve("jupyter_ai/config.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"model_provider_id\":\"openrouter:gone\"}");
        var pb = new ProcessBuilder("python3", "-I", SCRIPT.toString());
        pb.environment().put("JUPYTER_DATA_DIR", data.toString());
        pb.environment().put("JUPYTER_AI_BASE_URL", baseUrl());
        pb.environment().put("JUPYTER_AI_MODEL", DEFAULT);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        assertEquals("openrouter:" + DEFAULT, read(config).get("model_provider_id").asText());
    }
}
