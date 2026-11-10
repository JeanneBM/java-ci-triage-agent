package io.github.jeannebm.triage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the evaluation logs offline against scripted models. A model that reads the runbook and
 * claims a diagnosis for any line of a negative case must still be rejected by the guardrail.
 */
class EvalCasesTest {

    private static final Path CASES = Path.of("eval", "cases");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SdkTracerProvider TRACING = SdkTracerProvider.builder().build();

    @AfterAll
    static void closeTracing() {
        TRACING.close();
    }

    @ParameterizedTest
    @MethodSource("needsMoreEvidenceCases")
    void aModelThatAlwaysDiagnosesCannotDiagnoseANegativeCase(Path file) throws IOException {
        String log = Files.readString(file);
        var quotes = Stream.concat(log.lines().filter(line -> !line.isBlank()),
                Stream.of("BUILD FAILURE", "release version 21 not supported")).toList();
        for (String quote : quotes) {
            var service = new TriageService(alwaysDiagnoses(quote), JSON, TRACING);
            assertThat(diagnoses(service, log)).as("diagnosis quoting: %s", quote).isFalse();
        }
    }

    @ParameterizedTest
    @MethodSource("diagnosedCases")
    void quotingTheCompilerErrorDiagnosesAPositiveCase(Path file) throws IOException {
        String log = Files.readString(file);
        String errorLine = log.lines()
                .filter(line -> line.contains("[ERROR]") && line.contains("not supported"))
                .findFirst().orElseThrow().strip();
        var result = new TriageService(alwaysDiagnoses(errorLine), JSON, TRACING).triage(log);
        assertThat(result.status()).isEqualTo(TriageService.Status.DIAGNOSED);
        assertThat(result.nextStep()).isEqualTo(Runbook.mavenJdkRelease().nextStep());
    }

    static List<Path> diagnosedCases() throws IOException {
        return cases(TriageService.Status.DIAGNOSED);
    }

    static List<Path> needsMoreEvidenceCases() throws IOException {
        return cases(TriageService.Status.NEEDS_MORE_EVIDENCE);
    }

    /** Case files for an expected status, from {@code eval/cases/diagnosed} or {@code needs-more-evidence}. */
    static List<Path> cases(TriageService.Status expected) throws IOException {
        var directory = CASES.resolve(expected.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        try (var files = Files.list(directory)) {
            return files.filter(file -> file.toString().endsWith(".log")).sorted().toList();
        }
    }

    private static boolean diagnoses(TriageService service, String log) {
        try {
            return service.triage(log).status() == TriageService.Status.DIAGNOSED;
        } catch (TriageService.InvalidResponse e) {
            return false;
        }
    }

    private static ChatModel alwaysDiagnoses(String evidence) throws IOException {
        String answer = JSON.writeValueAsString(Map.of("status", "DIAGNOSED", "evidence", evidence));
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                AiMessage message = request.messages().getLast() instanceof ToolExecutionResultMessage
                        ? AiMessage.from(answer)
                        : AiMessage.from(ToolExecutionRequest.builder()
                                .id("runbook").name(TriageService.TOOL_NAME).arguments("{}").build());
                return ChatResponse.builder().aiMessage(message).build();
            }
        };
    }
}
