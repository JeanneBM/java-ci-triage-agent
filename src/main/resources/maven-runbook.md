# MAVEN-JDK-001 — Compiler release unsupported

Symptom: Maven reports `error: release version 21 not supported`.
Diagnosis: the compiler selected for the build does not support the requested Java release.
Check `mvn -version` on the CI runner. If a Maven toolchain is configured, check the JDK it selects too.
Use a JDK that supports release 21, then rerun the build.
