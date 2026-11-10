# Java CI Triage Agent

Maven build failure triage with Spring Boot, LangChain4j and Langfuse.

`POST /triage` accepts a Maven build log. The model can call `lookupRunbook` once to
read a bundled troubleshooting guide. The Java application validates the tool call
and accepts a diagnosis only when the guide was read and the cited evidence appears
verbatim in the log. The response includes a suggested next step for a human.
The only tool reads a classpath resource.

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
| `known-jdk-mismatch.log` | `DIAGNOSED` | Reads `MAVEN-JDK-001`, quotes the compiler error and suggests checking the CI JDK and Maven toolchain. |
| `insufficient-evidence.log` | `NEEDS_MORE_EVIDENCE` | Asks for the first compiler error and `mvn -version`. The scripted model skips the runbook. |

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

Runtime validation checks the response contract and evidence provenance.
The offline tests check the expected outcome for each fixture and exercise a model
that returns invented evidence. Real model responses can vary. This PoC covers one
Maven failure class and the evidence check does not establish that every claim in
the diagnosis follows from the log.

- [LangChain4j tool calling](https://docs.langchain4j.dev/tutorials/tools/)
- [Langfuse OpenTelemetry integration](https://langfuse.com/integrations/native/opentelemetry)
