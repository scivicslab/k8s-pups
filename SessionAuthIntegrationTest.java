import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.logging.Logger;

public class SessionAuthIntegrationTest {

    private static final Logger LOG = Logger.getLogger(SessionAuthIntegrationTest.class.getName());
    private static final String CONTROLLER_URL = "http://localhost:8080";
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        LOG.info("Starting Session Authorization Integration Test");

        testUnauthenticatedAccess();
        testNonExistentSession();
        testProxyPath();

        LOG.info("All tests completed");
    }

    private static void testUnauthenticatedAccess() throws Exception {
        LOG.info("\n=== Test 1: Unauthenticated access ===");
        String url = CONTROLLER_URL + "/session/fake-session-123/";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(new URI(url))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        LOG.info("Status: " + response.statusCode());
        LOG.info("Expected: 401");
        LOG.info("Result: " + (response.statusCode() == 401 ? "✓ PASS" : "✗ FAIL"));
    }

    private static void testNonExistentSession() throws Exception {
        LOG.info("\n=== Test 2: Session endpoint exists (no auth) ===");
        String url = CONTROLLER_URL + "/session/test-session/";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(new URI(url))
            .GET()
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.info("Status: " + response.statusCode());
            LOG.info("Endpoint reachable: YES");
        } catch (Exception e) {
            LOG.severe("Endpoint unreachable: " + e.getMessage());
        }
    }

    private static void testProxyPath() throws Exception {
        LOG.info("\n=== Test 3: Session proxy path ===");
        String sessionId = "test-session-for-proxy";
        String url = CONTROLLER_URL + "/session/" + sessionId + "/";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(new URI(url))
            .header("Authorization", "Bearer test-token")
            .GET()
            .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.info("Status: " + response.statusCode());
            LOG.info("Response body (first 100 chars): " + response.body().substring(0, Math.min(100, response.body().length())));
        } catch (Exception e) {
            LOG.info("Expected error (session doesn't exist): " + e.getMessage());
        }
    }
}
