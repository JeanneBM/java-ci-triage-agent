package io.github.jeannebm.triage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "triage.mode=demo")
class LangfuseExportTest {

    private static final LinkedBlockingQueue<Export> EXPORTS = new LinkedBlockingQueue<>();
    private static final HttpServer RECEIVER = startReceiver();

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private SdkTracerProvider tracerProvider;

    @DynamicPropertySource
    static void langfuseProperties(DynamicPropertyRegistry properties) {
        properties.add("triage.langfuse.host", () -> "http://127.0.0.1:" + RECEIVER.getAddress().getPort());
        properties.add("triage.langfuse.public-key", () -> "test-public");
        properties.add("triage.langfuse.secret-key", () -> "test-secret");
    }

    @AfterAll
    static void stopReceiver() {
        RECEIVER.stop(0);
    }

    @Test
    void exportsMetadataOnlyToTheLangfuseOtlpEndpoint() throws InterruptedException {
        var response = http.postForEntity("/triage", new TriageController.Request("""
                [ERROR] error: release version 21 not supported
                [INFO] PRIVATE_TEST_MARKER
                """), TriageService.Result.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(TriageService.Status.DIAGNOSED);

        assertThat(tracerProvider.forceFlush().join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
        var export = EXPORTS.poll(5, TimeUnit.SECONDS);
        assertThat(export).isNotNull();
        assertThat(export.method()).isEqualTo("POST");
        assertThat(export.version()).isEqualTo("4");
        assertThat(export.authorization()).isEqualTo("Basic dGVzdC1wdWJsaWM6dGVzdC1zZWNyZXQ=");
        assertThat(export.contentType()).isEqualTo("application/x-protobuf");
        assertThat(export.body())
                .contains("maven-ci-triage", "lookupRunbook", "MAVEN-JDK-001", "validate-response")
                .doesNotContain("PRIVATE_TEST_MARKER", "release version 21 not supported");
    }

    private static HttpServer startReceiver() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/public/otel/v1/traces", exchange -> {
                try (exchange) {
                    var headers = exchange.getRequestHeaders();
                    EXPORTS.add(new Export(exchange.getRequestMethod(), headers.getFirst("Authorization"),
                            headers.getFirst("x-langfuse-ingestion-version"), headers.getFirst("Content-Type"),
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
                    exchange.sendResponseHeaders(200, -1);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Export(String method, String authorization, String version, String contentType, String body) {}
}
