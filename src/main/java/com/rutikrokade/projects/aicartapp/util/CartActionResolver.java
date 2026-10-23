package com.rutikrokade.projects.aicartapp.util;

import java.util.*;
import com.rutikrokade.projects.aicartapp.dto.*;

/** Resolves model-proposed references against server-owned catalog and context. Never searches vectors. */
public final class CartActionResolver {

    private CartActionResolver() { }

    public static PreparedCartActionDto prepare(String message, ChatPlanDto plan, ChatTurnDto turn,
                                               CartDto cart, ChatContextDto context, ChatResponseDto response) {
        CartInstructionDto instruction = plan.getCartAction();
        if (instruction == null) throw new IllegalArgumentException("Missing cart instruction");
        PendingCartActionDto pending = context.getPendingCartAction();
        context.setPendingCartAction(null);
        context.setPendingClarification(null);
        if (instruction.getOperation() == CartInstructionDto.Operation.CHECKOUT) {
            ChatResponses.text(response, "CART_ACTION_UNAVAILABLE", "Checkout and order placement are not available. You can add, change or remove a dish from your cart.");
            return null;
        }
        // A single action cannot quietly discard another explicitly named dish.
        var named = namedItems(message, turn, cart);
        if (instruction.isMultipleItems() || named.size() > 1 || plan.getItemIds().size() > 1
                || plan.getReferencePositions().size() > 1 || plan.isUsePreviousRecommendations()) {
            clarify(response, context, null, "Please change one dish at a time. Which dish would you like to change first?");
            return null;
        }
        Long item = null;
        if (!plan.getItemIds().isEmpty()) {
            Long proposed = plan.getItemIds().getFirst();
            if (!named.contains(proposed)) throw new IllegalArgumentException("Cart item must be explicitly named");
            item = proposed;
        }
        if (!plan.getReferencePositions().isEmpty()) {
            int position = plan.getReferencePositions().getFirst();
            if (position < 1 || position > context.getRecommendationIds().size()) {
                clarify(response, context, null, "Which dish do you mean? Please give its name.");
                return null;
            }
            Long referenced = context.getRecommendationIds().get(position - 1);
            if (item != null && !item.equals(referenced)) throw new IllegalArgumentException("Conflicting cart references");
            item = referenced;
        }
        if (item == null && named.size() == 1) item = named.iterator().next();
        if (item == null) {
            item = switch (instruction.getReference()) {
                case LAST_CART -> context.getLastCartItemId();
                case SOLE_CART -> cart.getItems().size() == 1 ? cart.getItems().getFirst().getItemId() : null;
                case PENDING -> pending == null ? null : pending.getItemId();
                case EXPLICIT, NONE -> null;
            };
        }
        var operation = instruction.getOperation();
        Integer amount = instruction.getAmount();
        if (pending != null) {
            if (operation == null) operation = pending.getOperation();
            if (amount == null && operation == pending.getOperation()) amount = pending.getAmount();
        }
        var unresolved = new PendingCartActionDto();
        unresolved.setOperation(operation); unresolved.setAmount(amount); unresolved.setItemId(item);
        if (item == null || operation == null || operation != CartInstructionDto.Operation.REMOVE && amount == null) {
            clarify(response, context, unresolved, item == null ? "Which dish do you mean? Please give its name."
                    : "What quantity should this dish have, or how many would you like to add or remove?");
            return null;
        }
        int before = quantity(cart, item);
        long after = switch (operation) {
            case ADD -> (long) before + amount;
            case DECREASE -> (long) before - amount;
            case SET -> amount;
            case REMOVE -> 0;
            case CHECKOUT -> throw new IllegalArgumentException("Unexpected checkout operation");
        };
        if (after < 0 || after > 20 || operation != CartInstructionDto.Operation.REMOVE
                && (amount < 0 || amount > 20 || operation != CartInstructionDto.Operation.SET && amount == 0)) {
            var result = new CartActionResultDto();
            result.setOperation(operation); result.setItemId(item);
            result.setPreviousQuantity(before); result.setQuantity(before);
            result.setOutcome("CART_ACTION_REJECTED"); result.setReason("INVALID_QUANTITY");
            response.setActionResult(result);
            unresolved.setAmount(null);
            context.setPendingCartAction(unresolved);
            context.setLastCartItemId(null);
            ChatResponses.text(response, result.getOutcome(), "That change would exceed the allowed quantity. Each dish must have 0 to 20 units; additions and decreases must be positive.");
            return null;
        }
        return new PreparedCartActionDto(operation, item, before, (int) after);
    }

    public static int quantity(CartDto cart, Long itemId) {
        return cart.getItems().stream().filter(item -> item.getItemId().equals(itemId))
                .mapToInt(CartItemDto::getQuantity).findFirst().orElse(0);
    }

    private static Set<Long> namedItems(String message, ChatTurnDto turn, CartDto cart) {
        String lower = message.toLowerCase(Locale.ROOT);
        var result = new HashSet<Long>();
        for (var item : turn.getCatalog()) {
            if (lower.contains(item.get("name").toString().toLowerCase(Locale.ROOT))) result.add(((Number)item.get("id")).longValue());
        }
        for (var item : cart.getItems()) {
            if (lower.contains(item.getName().toLowerCase(Locale.ROOT))) result.add(item.getItemId());
        }
        return result;
    }

    private static void clarify(ChatResponseDto response, ChatContextDto context, PendingCartActionDto pending, String text) {
        context.setLastCartItemId(null);
        context.setPendingCartAction(pending);
        context.setPendingClarification(text);
        ChatResponses.text(response, "CLARIFICATION", text);
    }
}
