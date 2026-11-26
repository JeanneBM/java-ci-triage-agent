# Local validation — 26 November 2026

Validated the resolved merge of PR #2 (`589c801`) and main (`06cd659`).
The merge preserves demo/live observation types and structured, metadata-only
tracing while adopting runbook-owned advice and strict Maven error-line evidence.

## Build and tests

Java 21.0.13.1, Maven 3.9.11. Result: **BUILD SUCCESS**.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| TriageTest | 17 | 0 |
| EvalCasesTest | 10 | 0 |
| LangfuseExportTest | 1 | 0 |
| LiveEvalTest | 0 | 1 |
| Total | 28 | 1 |

The local runtime omits `ct.sym` and cannot dynamically attach Mockito's agent.
Verification used `mvn -B -ntp verify` with these environment-only options:

```text
-Dmaven.compiler.release=
-Dmaven.compiler.source=21
-Dmaven.compiler.target=21
-DargLine=-javaagent:<local-maven-cache>/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar
```

No POM settings were changed for this workaround. Java source and target remain
21. CI uses its normal full JDK and the standard `mvn verify` command.

`LangfuseExportTest` started the real Spring Boot endpoint and a local OTLP/HTTP
receiver. The receiver observed the expected trace/runbook/validation metadata
and did not receive the synthetic private log marker or the error-line text.
Additional tests distinguish demo `span` observations from live `generation`
observations using scripted model replies, with no provider calls.

## Packaged application HTTP smoke

Started the built JAR on `127.0.0.1:18080` with `TRIAGE_MODE=demo` and Langfuse
credentials unset. Submitted the repository's synthetic fixtures to `POST /triage`.

| Input | HTTP | Observed result |
| --- | ---: | --- |
| `examples/known-jdk-mismatch.log` | 200 | `DIAGNOSED`; exact quote `error: release version 21 not supported`; `MAVEN-JDK-001`; runbook-owned diagnosis |
| `examples/insufficient-evidence.log` | 200 | `NEEDS_MORE_EVIDENCE`; no diagnosis or evidence |
| Blank log | 400 | Rejected |

Both successful responses included a 32-character trace ID. The application was
stopped after the checks. `git diff --check` passed.

## Remaining validation

- Review and merge the updated PR after GitHub CI passes.
- Run the opt-in evaluation against a real model with a configured API account.
- Confirm metadata-only ingestion in an actual Langfuse project.

These checks establish offline/demo behavior and local OTLP export. They do not
establish live model quality or cloud Langfuse ingestion. No paid API or Azure
resources were used for this validation.
