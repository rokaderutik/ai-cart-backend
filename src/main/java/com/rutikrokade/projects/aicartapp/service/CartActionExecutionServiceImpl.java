package com.rutikrokade.projects.aicartapp.service;

import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CartActionExecutionServiceImpl implements CartActionExecutionService {

    private final ChatPersistenceService persistence;
    private final CartService carts;
    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public ChatResponseDto execute(String session, ChatTurnDto turn, PreparedCartActionDto action,
                                   ChatResponseDto response, ChatContextDto context, ChatCallBudget budget, long start) {
        persistence.requireAttempt(session, turn);
        if (action.getItemId() == null || action.getItemId() <= 0 || action.getQuantity() == null
                || action.getQuantity() < 0 || action.getQuantity() > 20 || action.getExpectedQuantity() == null
                || action.getOperation() == null || !turn.getRequestId().equals(response.getRequestId())) {
            throw new IllegalArgumentException("Invalid prepared cart action");
        }
        // Hold the menu row through the write so publication cannot change availability mid-action.
        var items = jdbc.queryForList("SELECT name, available FROM menu_item WHERE id=? FOR SHARE", action.getItemId());
        CartDto current = carts.getCart(session);
        int before = CartActionResolver.quantity(current, action.getItemId());
        String reason = null;
        String text;
        if (before != action.getExpectedQuantity()) {
            reason = "CART_CHANGED";
            text = "This dish's cart quantity changed while I was processing. Please submit your instruction again.";
        } else if (items.isEmpty()) {
            reason = "ITEM_NOT_FOUND";
            text = "That dish no longer exists. Please choose a current menu item.";
        } else if (action.getQuantity() > 0 && !Boolean.TRUE.equals(items.getFirst().get("available"))) {
            reason = "ITEM_UNAVAILABLE";
            text = "That dish is currently unavailable. You can remove it or choose another dish.";
        } else {
            current = carts.setItemQuantity(session, action.getItemId(), action.getQuantity());
            String name = items.getFirst().get("name").toString();
            text = before == action.getQuantity() ? name + " is already at quantity " + before + ". Your cart is unchanged."
                    : action.getQuantity() == 0 ? name + " was removed from your cart."
                    : name + " is now quantity " + action.getQuantity() + " in your cart.";
            context.setLastCartItemId(action.getItemId());
        }
        var result = new CartActionResultDto();
        result.setOperation(action.getOperation()); result.setItemId(action.getItemId());
        result.setPreviousQuantity(before); result.setQuantity(reason == null ? action.getQuantity() : before);
        result.setOutcome(reason != null ? "CART_ACTION_REJECTED" : before == action.getQuantity() ? "CART_UNCHANGED" : "CART_UPDATED");
        result.setReason(reason);
        response.setActionResult(result);
        response.setCart(current);
        context.setPendingCartAction(null);
        if (reason != null) {
            // A rejected change to B must not let a later "it" silently change previously successful A.
            context.setLastCartItemId(null);
            var pending = new PendingCartActionDto();
            pending.setItemId(action.getItemId());
            context.setPendingCartAction(pending);
        }
        context.setPendingClarification(null);
        ChatResponses.text(response, result.getOutcome(), text);
        ChatResponses.finish(response, budget, start);
        // The REQUIRED transaction includes cart rows, outcome, transcript, context and completion.
        ChatResponseDto completed = persistence.complete(session, turn, response, context);
        log.info("Cart tool request={} item={} previous={} quantity={} outcome={} reason={}", turn.getRequestId(),
                action.getItemId(), before, result.getQuantity(), result.getOutcome(), reason);
        return completed;
    }
}
