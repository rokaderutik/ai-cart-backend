package com.rutikrokade.projects.aicartapp.util;

import java.util.Objects;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.service.CartActionExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CartQuantityTool implements ToolCallback {

    public static final String NAME = "setCartItemQuantity";

    private final CartActionExecutionService execution;

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name(NAME)
                .description("Set one dish to the exact validated absolute quantity. Zero removes it. Use only the provided itemId and quantity.")
                .inputSchema("""
                        {"type":"object","properties":{"itemId":{"type":"integer"},"quantity":{"type":"integer"}},
                         "required":["itemId","quantity"],"additionalProperties":false}
                        """).build();
    }

    @Override
    public ToolMetadata getToolMetadata() { return ToolMetadata.builder().returnDirect(true).build(); }

    @Override
    public String call(String input) { throw new IllegalArgumentException("Server tool context is required"); }

    public static CartToolArgumentsDto validate(String input, PreparedCartActionDto expected) {
        if (input == null || input.length() > 1000) throw new IllegalArgumentException("Invalid tool arguments");
        var args = StructuredChatClient.JSON.readValue(input, CartToolArgumentsDto.class);
        if (args == null || !Objects.equals(args.getItemId(), expected.getItemId())
                || !Objects.equals(args.getQuantity(), expected.getQuantity())) {
            throw new IllegalArgumentException("Tool arguments do not match the prepared action");
        }
        return args;
    }

    @Override
    public String call(String input, ToolContext toolContext) {
        if (toolContext == null) throw new IllegalArgumentException("Server tool context is required");
        var values = toolContext.getContext();
        var action = (PreparedCartActionDto) values.get("action");
        if (action == null) throw new IllegalArgumentException("Prepared action is required");
        validate(input, action);
        var response = execution.execute((String)values.get("sessionId"), (ChatTurnDto)values.get("turn"), action,
                (ChatResponseDto)values.get("response"), (ChatContextDto)values.get("context"),
                (ChatCallBudget)values.get("budget"), (Long)values.get("start"));
        return StructuredChatClient.JSON.writeValueAsString(response);
    }
}
