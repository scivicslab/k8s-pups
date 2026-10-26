package com.scivicslab.k8spups.passport;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;

/** Calls the STS endpoint of an S3 server: AssumeRoleWithWebIdentity turns a signed token into temporary credentials. */
public final class S3Sts {

    /** Temporary credentials of the S3 server. */
    public record Credentials(String accessKey, String secretKey, String sessionToken, String expiration) {
    }

    /** Sends the form to the STS endpoint and returns the response body. */
    @FunctionalInterface
    public interface Post {
        String send(String url, String form) throws Exception;
    }

    private final String endpoint;
    private final Post post;

    public S3Sts(String endpoint, Post post) {
        this.endpoint = endpoint;
        this.post = post;
    }

    /** The HTTP/1.1 client of the controller. */
    public static Post http() {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        return (url, form) -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("STS " + url + " returned HTTP " + response.statusCode() + ": "
                        + response.body());
            }
            return response.body();
        };
    }

    public Credentials assumeRoleWithWebIdentity(String token, int durationSeconds) throws Exception {
        // The token goes in the body, not in the URL, so it does not appear in access logs.
        String form = "Action=AssumeRoleWithWebIdentity&Version=2011-06-15&DurationSeconds=" + durationSeconds
                + "&WebIdentityToken=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        return parse(post.send(endpoint, form));
    }

    static Credentials parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        return new Credentials(text(doc, "AccessKeyId"), text(doc, "SecretAccessKey"), text(doc, "SessionToken"),
                text(doc, "Expiration"));
    }

    private static String text(Document doc, String tag) {
        var nodes = doc.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            throw new IllegalStateException("the STS response has no " + tag);
        }
        return nodes.item(0).getTextContent().trim();
    }
}
