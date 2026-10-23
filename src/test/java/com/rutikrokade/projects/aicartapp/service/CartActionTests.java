package com.rutikrokade.projects.aicartapp.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.*;
import com.google.genai.Client;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.exception.ApiException;
import com.rutikrokade.projects.aicartapp.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;

class CartActionTests {

    private final ChatTurnDto turn = new ChatTurnDto();
    private final ChatContextDto context = new ChatContextDto();
    private final ChatResponseDto response = new ChatResponseDto();
    private CartDto cart = new CartDto("cart", List.of(), BigDecimal.ZERO, "INR");

    CartActionTests() {
        turn.setContext(context);
        turn.setCatalog(List.of(Map.of("id", 11L, "name", "Dal Tadka"), Map.of("id", 22L, "name", "Naan")));
    }

    @Test void ordinalThenAbsoluteThenRemovalResolveTheSameItem() {
        context.setRecommendationIds(List.of(22L, 11L));
        var add = plan(CartInstructionDto.Operation.ADD, 1);
        add.setReferencePositions(List.of(1));
        assertThat(resolve("Add the first dish", add)).isEqualTo(new PreparedCartActionDto(CartInstructionDto.Operation.ADD, 22L, 0, 1));
        context.setLastCartItemId(22L);
        var set = plan(CartInstructionDto.Operation.SET, 2);
        set.getCartAction().setReference(CartInstructionDto.Reference.LAST_CART);
        assertThat(resolve("Make that two", set).getQuantity()).isEqualTo(2);
        var remove = plan(CartInstructionDto.Operation.REMOVE, null);
        remove.getCartAction().setReference(CartInstructionDto.Reference.LAST_CART);
        assertThat(resolve("Remove it", remove).getQuantity()).isZero();
    }

    @Test void additiveAndAbsoluteQuantitiesDifferAndBoundsNeverClamp() {
        cart = cart(11L, 2);
        var add = plan(CartInstructionDto.Operation.ADD, 2); add.setItemIds(List.of(11L));
        assertThat(resolve("Add two Dal Tadka", add).getQuantity()).isEqualTo(4);
        add.getCartAction().setOperation(CartInstructionDto.Operation.SET);
        assertThat(resolve("Make Dal Tadka two", add).getQuantity()).isEqualTo(2);
        add.getCartAction().setOperation(CartInstructionDto.Operation.DECREASE);
        add.getCartAction().setAmount(3);
        assertThat(resolve("Remove three Dal Tadka", add)).isNull();
        assertThat(response.getActionResult().getReason()).isEqualTo("INVALID_QUANTITY");
        add.getCartAction().setOperation(CartInstructionDto.Operation.ADD);
        add.getCartAction().setAmount(Integer.MAX_VALUE);
        assertThat(resolve("Add too many Dal Tadka", add)).isNull();
    }

    @Test void missingDishCanBeResolvedWithoutLosingRequestedQuantity() {
        var add = plan(CartInstructionDto.Operation.ADD, 2);
        assertThat(resolve("Add two", add)).isNull();
        assertThat(response.getOutcome()).isEqualTo("CLARIFICATION");
        assertThat(context.getPendingCartAction().getAmount()).isEqualTo(2);
        var followup = plan(null, null); followup.setItemIds(List.of(11L));
        assertThat(resolve("Dal Tadka", followup).getQuantity()).isEqualTo(2);
        assertThat(context.getPendingCartAction()).isNull();
    }

    @Test void ambiguityMultipleItemsUnknownNamesAndForgedIdsNeverChooseAnArbitraryItem() {
        var plan = plan(CartInstructionDto.Operation.ADD, 1);
        plan.setReferencePositions(List.of(2));
        assertThat(resolve("Add the second dish", plan)).isNull();
        plan.setReferencePositions(List.of());
        assertThat(resolve("Add Dal Tadka and Naan", plan)).isNull();
        assertThat(response.getReply()).isNull(); // Completion, not the resolver, creates the final reply.
        plan.getCartAction().setMultipleItems(true);
        assertThat(resolve("Add all of those", plan)).isNull();
        plan.getCartAction().setMultipleItems(false);
        plan.setItemIds(List.of(11L));
        assertThatThrownBy(() -> resolve("Add an unknown dish", plan)).isInstanceOf(IllegalArgumentException.class);
        plan.setItemIds(List.of());
        context.setLastCartItemId(11L);
        plan.getCartAction().setReference(CartInstructionDto.Reference.EXPLICIT);
        assertThat(resolve("Add Moon Soup", plan)).isNull();
    }

    @Test void soleCartFallbackRequiresExactlyOneItemAndCheckoutNeverPreparesAnAction() {
        var plan = plan(CartInstructionDto.Operation.REMOVE, null);
        plan.getCartAction().setReference(CartInstructionDto.Reference.SOLE_CART);
        assertThat(resolve("Remove it", plan)).isNull();
        cart = cart(22L, 2);
        assertThat(resolve("Remove it", plan).getItemId()).isEqualTo(22L);
        plan.getCartAction().setOperation(CartInstructionDto.Operation.CHECKOUT);
        assertThat(resolve("Checkout", plan)).isNull();
        assertThat(response.getOutcome()).isEqualTo("CART_ACTION_UNAVAILABLE");
    }

    @Test void rejectedNewDishDoesNotLeaveAnOldCartFocusForCorrection() {
        context.setLastCartItemId(22L);
        var invalid = plan(CartInstructionDto.Operation.SET, 21); invalid.setItemIds(List.of(11L));
        assertThat(resolve("Make Dal Tadka 21", invalid)).isNull();
        assertThat(context.getLastCartItemId()).isNull();
        assertThat(context.getPendingCartAction().getItemId()).isEqualTo(11L);
        var correction = plan(CartInstructionDto.Operation.SET, 2);
        correction.getCartAction().setReference(CartInstructionDto.Reference.PENDING);
        assertThat(resolve("Make that two", correction).getItemId()).isEqualTo(11L);
        context.setLastCartItemId(22L);
        var unknown = plan(CartInstructionDto.Operation.ADD, 1);
        unknown.getCartAction().setReference(CartInstructionDto.Reference.EXPLICIT);
        assertThat(resolve("Add Moon Soup", unknown)).isNull();
        assertThat(context.getLastCartItemId()).isNull();
    }

    @Test void strictToolArgumentsRejectChangedValuesSessionFieldsCoercionsAndDuplicates() {
        var expected = new PreparedCartActionDto(CartInstructionDto.Operation.SET, 11L, 0, 2);
        for (String invalid : List.of("{\"itemId\":22,\"quantity\":2}", "{\"itemId\":11,\"quantity\":3}",
                "{\"itemId\":11,\"quantity\":2,\"sessionId\":\"foreign\"}", "{\"itemId\":\"11\",\"quantity\":2}",
                "{\"itemId\":11,\"quantity\":2.5}", "{\"itemId\":11,\"quantity\":2,\"quantity\":2}", "null")) {
            assertThatThrownBy(() -> CartQuantityTool.validate(invalid, expected)).isInstanceOf(RuntimeException.class);
        }
        var execution = mock(CartActionExecutionService.class);
        var tool = new CartQuantityTool(execution);
        assertThatThrownBy(() -> tool.call("{\"itemId\":11,\"quantity\":2}")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(execution);
    }

    @Test void nativeCallsAreInspectedWithoutExecutingAndRepairIsShared() {
        var model = mock(ChatModel.class);
        var execution = mock(CartActionExecutionService.class);
        var tool = new CartQuantityTool(execution);
        var client = new StructuredChatClient(model, mock(Client.class), "test-model");
        var expected = new PreparedCartActionDto(CartInstructionDto.Operation.ADD, 11L, 0, 1);
        when(model.call(any(Prompt.class))).thenReturn(nativeCall("bad", "{}"), nativeCall(CartQuantityTool.NAME, "{\"itemId\":11,\"quantity\":1}"));
        var budget = new ChatCallBudget();
        assertThat(client.cartToolArguments(expected, tool, budget)).contains("itemId");
        assertThat(budget.getCalls()).isEqualTo(2);
        assertThat(budget.isRepaired()).isTrue();
        verifyNoInteractions(execution);
        when(model.call(any(Prompt.class))).thenReturn(nativeCall("bad", "{}"));
        assertThatThrownBy(() -> client.cartToolArguments(expected, tool, budget)).isInstanceOf(ApiException.class);
        assertThat(budget.getCalls()).isEqualTo(3);
    }

    @Test void multipleNativeCallsAreRejectedBeforeAnyExecution() {
        var model = mock(ChatModel.class);
        var execution = mock(CartActionExecutionService.class);
        var tool = new CartQuantityTool(execution);
        var call = new AssistantMessage.ToolCall("1", "function", CartQuantityTool.NAME, "{\"itemId\":11,\"quantity\":1}");
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().toolCalls(List.of(call, call)).build()))));
        var budget = new ChatCallBudget(); budget.claimRepair();
        assertThatThrownBy(() -> new StructuredChatClient(model, mock(Client.class), "test-model")
                .cartToolArguments(new PreparedCartActionDto(CartInstructionDto.Operation.ADD, 11L, 0, 1), tool, budget))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(execution);
    }

    private ChatPlanDto plan(CartInstructionDto.Operation operation, Integer amount) {
        var plan = new ChatPlanDto(); plan.setIntent(ChatPlanDto.Intent.CART_ACTION);
        var action = new CartInstructionDto(); action.setOperation(operation); action.setAmount(amount);
        plan.setCartAction(action); return plan;
    }

    private PreparedCartActionDto resolve(String message, ChatPlanDto plan) {
        return CartActionResolver.prepare(message, plan, turn, cart, context, response);
    }

    private static CartDto cart(Long id, int quantity) {
        return new CartDto("cart", List.of(new CartItemDto(id, "Dal Tadka", quantity, BigDecimal.TEN,
                BigDecimal.TEN.multiply(BigDecimal.valueOf(quantity)), true)), BigDecimal.ZERO, "INR");
    }

    private static ChatResponse nativeCall(String name, String arguments) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(
                List.of(new AssistantMessage.ToolCall("1", "function", name, arguments))).build())));
    }
}
