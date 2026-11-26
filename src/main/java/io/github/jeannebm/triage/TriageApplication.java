package io.github.jeannebm.triage;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TriageApplication {

    public static void main(String[] args) {
        SpringApplication.run(TriageApplication.class, args);
    }

    @Bean
    ChatModel chatModel(
            @Value("${triage.mode}") String mode,
            @Value("${triage.provider}") String provider,
            @Value("${triage.openai.api-key}") String apiKey,
            @Value("${triage.openai.model}") String modelName,
            @Value("${triage.azure.endpoint}") String azureEndpoint,
            @Value("${triage.azure.api-key}") String azureKey,
            @Value("${triage.azure.deployment}") String azureDeployment) {
        return switch (mode) {
            case "demo" -> new DemoChatModel();
            case "live" -> liveModelBuilder(provider, apiKey, modelName,
                    azureEndpoint, azureKey, azureDeployment).build();
            default -> throw new IllegalArgumentException("TRIAGE_MODE must be demo or live");
        };
    }

    static OpenAiChatModel.OpenAiChatModelBuilder liveModelBuilder(
            String provider, String apiKey, String modelName,
            String azureEndpoint, String azureKey, String azureDeployment) {
        var builder = OpenAiChatModel.builder()
                .responseFormat("json_object")
                .timeout(Duration.ofSeconds(15))
                .maxRetries(0)
                .maxTokens(450);
        switch (provider) {
            case "openai" -> {
                if (apiKey.isBlank() || modelName.isBlank()) {
                    throw new IllegalArgumentException("Live mode requires OPENAI_API_KEY and a model name");
                }
                builder.apiKey(apiKey).modelName(modelName);
            }
            case "azure" -> {
                if (azureKey.isBlank() || azureDeployment.isBlank()) {
                    throw new IllegalArgumentException(
                            "Azure live mode requires AZURE_OPENAI_API_KEY and AZURE_OPENAI_DEPLOYMENT");
                }
                // Azure v1 accepts the OpenAI request contract and the deployment in `model`.
                // The Azure key is sent only to the configured endpoint, in the api-key header.
                builder.baseUrl(azureBaseUrl(azureEndpoint))
                        .customHeaders(Map.of("api-key", azureKey))
                        .modelName(azureDeployment);
            }
            default -> throw new IllegalArgumentException("TRIAGE_PROVIDER must be openai or azure");
        }
        return builder;
    }

    static String azureBaseUrl(String endpoint) {
        String message = "AZURE_OPENAI_ENDPOINT must be an HTTPS resource URL or its /openai/v1 URL";
        try {
            URI uri = URI.create(endpoint.strip());
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            String path = uri.getPath().replaceFirst("/+$", "");
            if (!path.isEmpty() && !"/openai/v1".equals(path)) {
                throw new IllegalArgumentException();
            }
            return "https://" + uri.getRawAuthority() + "/openai/v1/";
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(message);
        }
    }

    @Bean(destroyMethod = "close")
    SdkTracerProvider tracerProvider(
            @Value("${triage.langfuse.host}") String host,
            @Value("${triage.langfuse.public-key}") String publicKey,
            @Value("${triage.langfuse.secret-key}") String secretKey) {
        var provider = SdkTracerProvider.builder();
        if (publicKey.isBlank() && secretKey.isBlank()) {
            return provider.build();
        }
        if (publicKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalArgumentException("Langfuse requires both project keys");
        }

        var credential = Base64.getEncoder().encodeToString(
                (publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
        var exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(host.replaceFirst("/+$", "") + "/api/public/otel/v1/traces")
                .addHeader("Authorization", "Basic " + credential)
                .addHeader("x-langfuse-ingestion-version", "4")
                .setTimeout(Duration.ofSeconds(5))
                .build();
        return provider.addSpanProcessor(BatchSpanProcessor.builder(exporter)
                .setScheduleDelay(Duration.ofSeconds(1))
                .build()).build();
    }
}
