package io.github.jeannebm.triage;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENDPOINT = "https://triage-example.openai.azure.com";

    @ParameterizedTest
    @CsvSource({"openai,https://api.openai.com/v1/chat/completions,gpt-4.1-mini",
            "azure,https://triage-example.openai.azure.com/openai/v1/chat/completions,triage-deployment"})
    void routesTheWholeToolExchangeToTheSelectedProvider(String provider, String url, String modelName)
            throws Exception {
        var http = new RecordingClient();
        var model = TriageApplication.liveModelBuilder(provider, "openai-test-key", "gpt-4.1-mini",
                        ENDPOINT, "azure-test-key", "triage-deployment")
                .httpClientBuilder(http).build();

        try (var tracing = SdkTracerProvider.builder().build()) {
            var service = new TriageService(model, JSON, tracing, "live");
            var result = service.triage("[ERROR] error: release version 21 not supported");
            assertThat(result.status()).isEqualTo(TriageService.Status.DIAGNOSED);
            assertThat(result.runbookId()).isEqualTo("MAVEN-JDK-001");
        }

        assertThat(http.requests).hasSize(2);
        for (var request : http.requests) {
            assertThat(request.url()).isEqualTo(url);
            var body = JSON.readTree(request.body());
            assertThat(body.path("model").asText()).isEqualTo(modelName);
            assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
            assertThat(body.path("max_tokens").asInt()).isEqualTo(450);
            assertThat(request.body()).doesNotContain("openai-test-key", "azure-test-key");
            if ("azure".equals(provider)) {
                assertThat(request.headers()).containsEntry("api-key", List.of("azure-test-key"));
                assertThat(request.headers()).doesNotContainKey("Authorization");
            } else {
                assertThat(request.headers()).containsEntry("Authorization", List.of("Bearer openai-test-key"));
                assertThat(request.headers()).doesNotContainKey("api-key");
            }
        }
        var first = JSON.readTree(http.requests.getFirst().body());
        assertThat(first.path("tools").get(0).path("function").path("name").asText())
                .isEqualTo("lookupRunbook");
        var messages = JSON.readTree(http.requests.getLast().body()).path("messages");
        assertThat(messages.get(messages.size() - 1).path("role").asText()).isEqualTo("tool");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://triage-example.openai.azure.com",
            "https://triage-example.openai.azure.com/",
            "https://triage-example.openai.azure.com/openai/v1",
            "https://triage-example.openai.azure.com/openai/v1/"})
    void acceptsResourceAndV1Endpoints(String endpoint) {
        assertThat(TriageApplication.azureBaseUrl(endpoint)).isEqualTo(ENDPOINT + "/openai/v1/");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not a URL", "http://triage-example.openai.azure.com",
            "https://user:private-value@triage-example.openai.azure.com",
            "https://triage-example.openai.azure.com/openai/deployments/test",
            "https://triage-example.openai.azure.com?api-key=private-value"})
    void rejectsWrongEndpointsWithoutEchoingTheirContents(String endpoint) {
        assertThatThrownBy(() -> TriageApplication.azureBaseUrl(endpoint))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AZURE_OPENAI_ENDPOINT must be an HTTPS resource URL or its /openai/v1 URL");
    }

    @Test
    void azureNeedsItsOwnConfigurationAndDoesNotFallBackToOpenAi() {
        assertThatThrownBy(() -> TriageApplication.liveModelBuilder(
                "azure", "openai-key", "openai-model", ENDPOINT, "", "deployment"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AZURE_OPENAI_API_KEY");
        assertThatThrownBy(() -> TriageApplication.liveModelBuilder(
                "azure", "openai-key", "openai-model", ENDPOINT, "azure-key", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AZURE_OPENAI_DEPLOYMENT");
        assertThatThrownBy(() -> TriageApplication.liveModelBuilder(
                "unknown", "openai-key", "openai-model", ENDPOINT, "azure-key", "deployment"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TRIAGE_PROVIDER must be openai or azure");
    }

    @Test
    void demoStillNeedsNoProviderCredentials() {
        var model = new TriageApplication().chatModel("demo", "azure", "", "", "", "", "");
        assertThat(model).isInstanceOf(DemoChatModel.class);
    }

    private static final class RecordingClient implements HttpClient, HttpClientBuilder {
        final List<HttpRequest> requests = new ArrayList<>();
        final ArrayDeque<String> replies = new ArrayDeque<>(List.of("""
                {"id":"first","object":"chat.completion","created":1,"model":"test-model",
                 "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant",
                 "tool_calls":[{"id":"lookup","type":"function","function":{
                 "name":"lookupRunbook","arguments":"{}"}}]}}]}
                """, """
                {"id":"second","object":"chat.completion","created":2,"model":"test-model",
                 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant",
                 "content":"{\\"status\\":\\"DIAGNOSED\\",\\"evidence\\":\\"error: release version 21 not supported\\"}"}}]}
                """));
        private Duration connectTimeout;
        private Duration readTimeout;

        @Override public Duration connectTimeout() { return connectTimeout; }
        @Override public HttpClientBuilder connectTimeout(Duration timeout) { connectTimeout = timeout; return this; }
        @Override public Duration readTimeout() { return readTimeout; }
        @Override public HttpClientBuilder readTimeout(Duration timeout) { readTimeout = timeout; return this; }
        @Override public HttpClient build() { return this; }

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            requests.add(request);
            return SuccessfulHttpResponse.builder().statusCode(200).headers(Map.of())
                    .body(replies.remove()).build();
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
            throw new AssertionError("Streaming is not used by triage");
        }
    }
}
