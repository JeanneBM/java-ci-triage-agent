package io.github.jeannebm.triage;

import java.util.List;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

final class DemoChatModel implements ChatModel {

    private static final String ERROR = "error: release version 21 not supported";

    @Override
    public ChatResponse doChat(ChatRequest request) {
        var messages = request.messages();
        var last = messages.getLast();
        if (last instanceof ToolExecutionResultMessage) {
            return response(AiMessage.from("""
                    {
                      "status":"DIAGNOSED",
                      "diagnosis":"The compiler selected by Maven does not support Java release 21.",
                      "evidence":"error: release version 21 not supported",
                      "nextStep":"Check mvn -version and the Maven toolchain on CI. Select a JDK that supports release 21, then rerun."
                    }
                    """));
        }
        if (last instanceof UserMessage user && user.singleText().contains(ERROR)) {
            return response(AiMessage.from(List.of(ToolExecutionRequest.builder()
                    .id("demo-runbook-call")
                    .name(TriageService.TOOL_NAME)
                    .arguments("{}")
                    .build())));
        }
        return response(AiMessage.from("""
                {
                  "status":"NEEDS_MORE_EVIDENCE",
                  "nextStep":"Provide the first compiler error and the output of mvn -version."
                }
                """));
    }

    private static ChatResponse response(AiMessage message) {
        return ChatResponse.builder().aiMessage(message).build();
    }
}
