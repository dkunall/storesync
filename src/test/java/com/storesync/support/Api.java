package com.storesync.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * A small HTTP client that never throws on status codes, so a 409 is as easy to assert as a 200. Immutable;
 * {@code as} and {@code in} return copies.
 */
public final class Api {

    public record Response(int status, JsonNode body, HttpHeaders headers) {

        public Response expect(int expected) {
            if (status != expected) {
                throw new AssertionError("expected HTTP " + expected + " but got " + status + ": " + body);
            }
            return this;
        }

        public String text(String field) {
            return body.path(field).asText();
        }

        public String header(String name) {
            return headers.getFirst(name);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final RestClient client;
    private final Map<String, String> defaults;

    private Api(RestClient client, Map<String, String> defaults) {
        this.client = client;
        this.defaults = defaults;
    }

    public static Api atUrl(String base) {
        return new Api(RestClient.builder().baseUrl(base)
            .requestFactory(new JdkClientHttpRequestFactory()).build(), Map.of());
    }

    public static Api at(int port) {
        return new Api(RestClient.builder().baseUrl("http://localhost:" + port)
            .requestFactory(new JdkClientHttpRequestFactory()).build(), Map.of());
    }

    public Api as(String bearer) {
        return with(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }

    public Api with(String header, String value) {
        Map<String, String> copy = new LinkedHashMap<>(defaults);
        copy.put(header, value);
        return new Api(client, copy);
    }

    public Response get(String path) {
        return send(HttpMethod.GET, path, null, Map.of());
    }

    public Response get(String path, Map<String, String> headers) {
        return send(HttpMethod.GET, path, null, headers);
    }

    public Response post(String path, Object body) {
        return send(HttpMethod.POST, path, body, Map.of());
    }

    public Response post(String path, Object body, Map<String, String> headers) {
        return send(HttpMethod.POST, path, body, headers);
    }

    public Response put(String path, Object body) {
        return send(HttpMethod.PUT, path, body, Map.of());
    }

    public Response patch(String path, Object body, Map<String, String> headers) {
        return send(HttpMethod.PATCH, path, body, headers);
    }

    public Response delete(String path) {
        return send(HttpMethod.DELETE, path, null, Map.of());
    }

    public Response send(HttpMethod method, String path, Object body, Map<String, String> headers) {
        RestClient.RequestBodySpec spec = client.method(method).uri(path).headers(h -> {
            defaults.forEach(h::set);
            headers.forEach(h::set);
        });
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body instanceof String s ? s : write(body));
        }
        return spec.exchange((request, response) -> {
            byte[] bytes = response.getBody().readAllBytes();
            return new Response(response.getStatusCode().value(), bytes.length == 0 ? MAPPER.nullNode() : parse(bytes),
                response.getHeaders());
        });
    }

    private static JsonNode parse(byte[] bytes) {
        try {
            return MAPPER.readTree(bytes);
        } catch (IOException e) {
            return MAPPER.getNodeFactory().textNode(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    private static String write(Object body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
