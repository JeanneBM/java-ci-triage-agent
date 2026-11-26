package io.github.jeannebm.triage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;

/**
 * A troubleshooting runbook. The model reads only {@link #guide()}. The symptom, diagnosis and
 * next step are owned by the application, so text in a build log cannot change what a person is told.
 */
record Runbook(String id, Pattern symptom, String diagnosis, String nextStep, String guide) {

    static Runbook mavenJdkRelease() throws IOException {
        return new Runbook(
                "MAVEN-JDK-001",
                Pattern.compile("release version \\d+ not supported"),
                "The compiler selected by Maven does not support the requested Java release.",
                "Check mvn -version on the CI runner and the JDK selected by any Maven toolchain. "
                        + "Use a JDK that supports the requested release, then rerun the build.",
                new ClassPathResource("maven-runbook.md").getContentAsString(StandardCharsets.UTF_8));
    }

    boolean showsSymptom(String evidence) {
        return symptom.matcher(evidence).find();
    }
}
