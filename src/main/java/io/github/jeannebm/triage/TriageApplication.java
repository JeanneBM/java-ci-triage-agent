package io.github.jeannebm.triage;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

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
            @Value("${triage.openai.api-key}") String apiKey,
            @Value("${triage.openai.model}") String modelName) {
        return switch (mode) {
            case "demo" -> new DemoChatModel();
            case "live" -> {
                if (apiKey.isBlank() || modelName.isBlank()) {
                    throw new IllegalArgumentException("Live mode requires OPENAI_API_KEY and a model name");
                }
                yield OpenAiChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .timeout(Duration.ofSeconds(15))
                        .maxRetries(0)
                        .maxTokens(450)
                        .build();
            }
            default -> throw new IllegalArgumentException("TRIAGE_MODE must be demo or live");
        };
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
