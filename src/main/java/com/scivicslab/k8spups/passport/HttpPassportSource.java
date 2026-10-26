package com.scivicslab.k8spups.passport;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Fetches the Passport claim {@code {"ga4gh_passport_v1": [...]}} of a user from a Visa issuer over HTTP. */
public final class HttpPassportSource implements PassportGrants.PassportSource, VisaCheck.JwksSource {

    private static final ObjectMapper JSON = new ObjectMapper();

    // HTTP/1.1 only: an h2c upgrade attempt breaks some servers.
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String passportUrlTemplate;

    /** @param passportUrlTemplate the Passport URL with the placeholder {sub} for the user name */
    public HttpPassportSource(String passportUrlTemplate) {
        this.passportUrlTemplate = passportUrlTemplate;
    }

    /** The Passport URL of a user: the template with {sub} replaced by the user name, encoded for a URL path. */
    static String urlFor(String template, String user) {
        // URLEncoder writes a space as "+", which is a space only in a query string; in a path it must be %20.
        return template.replace("{sub}", URLEncoder.encode(user, StandardCharsets.UTF_8).replace("+", "%20"));
    }

    @Override
    public List<String> visas(String user) throws Exception {
        String body = get(urlFor(passportUrlTemplate, user));
        Map<String, Object> claim = JSON.readValue(body, new TypeReference<>() {
        });
        Object visas = claim.get("ga4gh_passport_v1");
        if (!(visas instanceof List<?> list)) {
            throw new IllegalStateException("the response has no ga4gh_passport_v1 array");
        }
        return list.stream().map(String::valueOf).toList();
    }

    @Override
    public String fetch(String jwksUri) throws Exception {
        return get(jwksUri);
    }

    private String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(url + " returned HTTP " + response.statusCode());
        }
        return response.body();
    }
}
