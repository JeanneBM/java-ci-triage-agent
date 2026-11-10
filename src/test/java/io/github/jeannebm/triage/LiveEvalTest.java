package io.github.jeannebm.triage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in evaluation against the live model. It sends the synthetic logs in {@code eval/cases} to the
 * provider and can incur charges. Run with {@code -Dtest=LiveEvalTest -Deval.live=true}.
 */
@EnabledIfSystemProperty(named = "eval.live", matches = "true")
class LiveEvalTest {

    private static final int RUNS = Integer.getInteger("eval.runs", 3);
    private static final String PROVIDER_ERROR = "PROVIDER_ERROR";
    private static final String REJECTED = "REJECTED: ";

    @Test
    void runsEveryCaseAgainstTheLiveModel() throws Exception {
        var app = new TriageApplication();
        String modelName = env("OPENAI_MODEL", "gpt-4.1-mini");
        var model = app.chatModel("live", env("OPENAI_API_KEY", ""), modelName);
        var tracing = app.tracerProvider(env("LANGFUSE_HOST", "https://cloud.langfuse.com"),
                env("LANGFUSE_PUBLIC_KEY", ""), env("LANGFUSE_SECRET_KEY", ""));

        var runs = new ArrayList<Run>();
        try (tracing) {
            var service = new TriageService(model, new ObjectMapper(), tracing);
            for (var expected : TriageService.Status.values()) {
                for (Path file : EvalCasesTest.cases(expected)) {
                    String log = Files.readString(file);
                    for (int i = 0; i < RUNS; i++) {
                        runs.add(new Run(file.getFileName().toString(), expected, outcome(service, log)));
                    }
                }
            }
        }

        String report = report(modelName, runs);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "eval-report.md"), report);
        System.out.println(report);

        assertThat(runs).as("negative cases must never be diagnosed")
                .noneMatch(run -> run.expected() == TriageService.Status.NEEDS_MORE_EVIDENCE
                        && run.outcome().equals(TriageService.Status.DIAGNOSED.name()));
    }

    private static String outcome(TriageService service, String log) {
        try {
            return service.triage(log).status().name();
        } catch (TriageService.InvalidResponse e) {
            return TriageService.MODEL_REQUEST_FAILED.equals(e.getMessage())
                    ? PROVIDER_ERROR
                    : REJECTED + e.getMessage();
        }
    }

    private static String report(String modelName, List<Run> runs) {
        var out = new StringBuilder("# Live evaluation\n\nModel: `%s`. Runs per case: %d.\n\n"
                .formatted(modelName, RUNS));
        out.append("| Case | Expected | Correct | Outcomes |\n| --- | --- | --- | --- |\n");
        runs.stream()
                .collect(Collectors.groupingBy(Run::file, LinkedHashMap::new, Collectors.toList()))
                .forEach((file, caseRuns) -> {
                    var outcomes = caseRuns.stream()
                            .collect(Collectors.groupingBy(Run::outcome, TreeMap::new, Collectors.counting()))
                            .entrySet().stream()
                            .map(entry -> entry.getKey() + " × " + entry.getValue())
                            .collect(Collectors.joining("<br>"));
                    out.append("| `%s` | %s | %d/%d | %s |\n".formatted(file, caseRuns.getFirst().expected(),
                            caseRuns.stream().filter(Run::correct).count(), caseRuns.size(), outcomes));
                });
        out.append("\nCorrect: %d/%d. Rejected by the guardrail: %d. Provider errors: %d.\n".formatted(
                runs.stream().filter(Run::correct).count(), runs.size(),
                runs.stream().filter(run -> run.outcome().startsWith(REJECTED)).count(),
                runs.stream().filter(run -> run.outcome().equals(PROVIDER_ERROR)).count()));
        return out.toString();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private record Run(String file, TriageService.Status expected, String outcome) {
        boolean correct() {
            return outcome.equals(expected.name());
        }
    }
}
