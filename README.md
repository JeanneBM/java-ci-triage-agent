# Java CI Triage Agent

Maven build failure triage with Spring Boot, LangChain4j and Langfuse.

`POST /triage` accepts a Maven build log. The model can call `lookupRunbook` once to
read a bundled troubleshooting guide, then returns only a status and, for a diagnosis,
a quote from the log. The Java application validates the tool call and accepts a
diagnosis only when the guide was read and the quote comes from an `[ERROR]` line of
the log and shows the symptom the runbook describes. The diagnosis and the suggested
next step for a human come from the runbook, not from the model, so text in a build
log cannot change what the service tells a person to do. The only tool reads a
classpath resource.

## Run locally

Use Java 21 and Maven 3.9+. Start in the default demo mode, which uses scripted model
responses and requires no account, API key or paid calls.

```bash
mvn -B -ntp verify
mvn spring-boot:run
```

In another terminal (Bash, curl and jq):

```bash
for log in examples/known-jdk-mismatch.log examples/insufficient-evidence.log; do
  jq -n --rawfile log "$log" '{log: $log}' |
    curl -fsS http://127.0.0.1:8080/triage \
      -H 'Content-Type: application/json' --data-binary @- | jq
done
```

| Fixture | Expected status | Expected behavior |
| --- | --- | --- |
| `known-jdk-mismatch.log` | `DIAGNOSED` | Reads `MAVEN-JDK-001`, quotes the compiler error and returns the runbook's next step: check the CI JDK and Maven toolchain. |
| `insufficient-evidence.log` | `NEEDS_MORE_EVIDENCE` | Returns a fixed request for the first compiler error and `mvn -version`. The scripted model skips the runbook. |

Invalid input returns HTTP 400. A provider failure or an invalid model response returns
HTTP 502. The service binds to localhost and accepts logs up to 12,000 characters.

## Live model and Langfuse

Set `TRIAGE_MODE=live` and provide `OPENAI_API_KEY` through your environment.
`OPENAI_MODEL` defaults to `gpt-4.1-mini`. This mode sends the submitted log to
the model provider and can incur charges. Use the synthetic fixtures for the demo.

For either mode, set `LANGFUSE_PUBLIC_KEY` and `LANGFUSE_SECRET_KEY` for your
project. `LANGFUSE_HOST` defaults to `https://cloud.langfuse.com`. Restart the
application after changing environment variables. In Langfuse, find the returned
`traceId`. The known failure shows two model calls around the runbook lookup.
Both paths show a `validate-response` span and the final status. Spans export
via OTLP/HTTP and contain the runbook ID and validation outcome. Raw logs, prompts,
tool arguments, runbook text and model answers stay out of the spans.

Runtime validation checks the response contract and evidence provenance. The model may
return only `status` and `evidence`; any other field, Markdown around the JSON, or an
abstention that cites evidence is rejected. The offline tests check the expected outcome
for each fixture and cover every rejection path. A local OTLP receiver test checks the
export request and verifies the trace omits raw log text. Real model responses can vary.
This PoC covers one Maven failure class. Anyone who can write an `[ERROR]` line with the
symptom into a build log can still trigger this diagnosis; the response then carries only
the runbook's text.

## Evaluation

`eval/cases` holds synthetic logs grouped by the expected status, `diagnosed` and
`needs-more-evidence`. They include near misses (another compiler error, a test that
prints the symptom) and logs with injected instructions. `EvalCasesTest` runs them in
every build against scripted models: for each negative case, a model that reads the
runbook and quotes any line of the log as evidence must still be rejected.

To measure a live model, run each case several times:

```bash
OPENAI_API_KEY=... mvn -B -ntp test -Dtest=LiveEvalTest -Deval.live=true -Deval.runs=3
```

This sends the synthetic logs to the model provider and can incur charges. The test
writes `target/eval-report.md` with the outcomes per case: the returned status, a
guardrail rejection with its reason, or a provider error. It fails if any negative case
is diagnosed. With the Langfuse keys set, the runs are traced as well.

- [LangChain4j tool calling](https://docs.langchain4j.dev/tutorials/tools/)
- [Langfuse OpenTelemetry integration](https://langfuse.com/integrations/native/opentelemetry)
