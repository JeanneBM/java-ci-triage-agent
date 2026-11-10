package io.github.jeannebm.triage;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TriageTest {

    private final ObjectMapper json = new ObjectMapper();
    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();

    @AfterEach
    void closeProvider() {
        provider.close();
    }

    @Test
    void diagnosesKnownFailureAndTracesTheRunbookAndValidation() throws Exception {
        var result = endpoint(new DemoChatModel()).perform(post("/triage")
                        .contentType(MediaType.APPLICATION_JSON).content(fixture("known-jdk-mismatch.log")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DIAGNOSED"))
                .andExpect(jsonPath("$.evidence").value("error: release version 21 not supported"))
                .andExpect(jsonPath("$.runbookId").value("MAVEN-JDK-001"))
                .andReturn();

        String traceId = json.readTree(result.getResponse().getContentAsString()).get("traceId").asText();
        assertThat(exporter.getFinishedSpanItems()).extracting(SpanData::getName)
                .containsExactly("model-call", "lookupRunbook", "model-call", "validate-response", "maven-ci-triage");
        var root = exporter.getFinishedSpanItems().getLast();
        assertThat(exporter.getFinishedSpanItems()).allMatch(span -> span.getTraceId().equals(traceId));
        assertThat(exporter.getFinishedSpanItems().subList(0, 4))
                .allMatch(span -> span.getParentSpanId().equals(root.getSpanId()));
        var validation = exporter.getFinishedSpanItems().get(3);
        assertThat(validation.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")))
                .isEqualTo("{\"validation\":\"passed\"}");
    }

    @Test
    void asksForEvidenceWithoutCallingTheTool() throws Exception {
        endpoint(new DemoChatModel()).perform(post("/triage")
                        .contentType(MediaType.APPLICATION_JSON).content(fixture("insufficient-evidence.log")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_MORE_EVIDENCE"))
                .andExpect(jsonPath("$.diagnosis").doesNotExist())
                .andExpect(jsonPath("$.runbookId").doesNotExist())
                .andExpect(jsonPath("$.nextStep").isNotEmpty());

        assertThat(exporter.getFinishedSpanItems()).extracting(SpanData::getName)
                .containsExactly("model-call", "validate-response", "maven-ci-triage");
    }

    @Test
    void rejectsInventedEvidenceAfterTheRunbookWasRead() throws Exception {
        var model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                if (request.messages().size() == 2) {
                    return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                            .id("runbook").name(TriageService.TOOL_NAME).arguments("{}").build())).build();
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("""
                        {"status":"DIAGNOSED","diagnosis":"JDK mismatch",
                         "evidence":"Invented compiler error","nextStep":"Check the JDK"}
                        """)).build();
            }
        };
        endpoint(model).perform(post("/triage")
                        .contentType(MediaType.APPLICATION_JSON).content(fixture("known-jdk-mismatch.log")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.evidence").doesNotExist());
    }

    private MockMvc endpoint(ChatModel model) throws Exception {
        var service = new TriageService(model, json, provider);
        return MockMvcBuilders.standaloneSetup(new TriageController(service)).build();
    }

    private String fixture(String name) throws Exception {
        return json.writeValueAsString(new TriageController.Request(Files.readString(Path.of("examples", name))));
    }
}
