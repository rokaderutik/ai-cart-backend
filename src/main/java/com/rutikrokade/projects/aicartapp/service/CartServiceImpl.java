package com.rutikrokade.projects.aicartapp.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.rutikrokade.projects.aicartapp.dto.CartDto;
import com.rutikrokade.projects.aicartapp.dto.CartItemDto;
import com.rutikrokade.projects.aicartapp.entity.Cart;
import com.rutikrokade.projects.aicartapp.entity.CartItem;
import com.rutikrokade.projects.aicartapp.entity.MenuItem;
import com.rutikrokade.projects.aicartapp.exception.ApiException;
import com.rutikrokade.projects.aicartapp.repository.CartItemRepository;
import com.rutikrokade.projects.aicartapp.repository.CartRepository;
import com.rutikrokade.projects.aicartapp.repository.MenuItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CartServiceImpl implements CartService {

    private final SessionService sessionService;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final MenuItemRepository menuItemRepository;

    @Override
    @Transactional(readOnly = true)
    public CartDto getCart(String sessionId) {
        sessionService.requireActiveSession(sessionId);
        Cart cart = requireCart(sessionId);
        log.debug("Loading cart: cartId={}", cart.getId());
        return toDto(cart);
    }

    @Override
    @Transactional
    public CartDto setItemQuantity(String sessionId, Long itemId, Integer quantity) {
        // Serialize changes even when the cart does not yet contain this dish.
        sessionService.lockActiveSession(sessionId);
        if (quantity == null || quantity < 0 || quantity > 20) {
            log.debug("Cart mutation rejected an invalid quantity");
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY", "Quantity must be between 0 and 20");
        }
        if (itemId == null || itemId <= 0) {
            log.debug("Cart mutation rejected a missing or non-positive menu item ID");
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ITEM_ID", "A positive menu item ID is required");
        }
        MenuItem menuItem = menuItemRepository.findById(itemId).orElseThrow(() -> {
            log.debug("Cart mutation rejected an unknown menu item: itemId={}", itemId);
            return new ApiException(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "Menu item was not found");
        });
        if (quantity > 0 && !menuItem.isAvailable()) {
            log.debug("Cart mutation rejected an unavailable menu item: itemId={}", itemId);
            throw new ApiException(HttpStatus.CONFLICT, "ITEM_UNAVAILABLE", "Menu item is currently unavailable");
        }
        Cart cart = requireCart(sessionId);
        log.info("Setting cart item quantity: cartId={}, itemId={}, quantity={}", cart.getId(), itemId, quantity);
        Optional<CartItem> existing = cartItemRepository.findByCart_IdAndMenuItem_Id(cart.getId(), itemId);
        if (quantity == 0) {
            existing.ifPresent(cartItemRepository::delete);
        } else if (existing.isPresent()) {
            existing.get().setQuantity(quantity);
        } else {
            cartItemRepository.save(new CartItem(UUID.randomUUID(), cart, menuItem, quantity));
        }
        cartItemRepository.flush();
        log.debug("Cart item mutation flushed: cartId={}, itemId={}", cart.getId(), itemId);
        return toDto(cart);
    }

    private Cart requireCart(String sessionId) {
        return cartRepository.findBySession_Id(sessionId)
                .orElseThrow(() -> new IllegalStateException("Active session has no cart"));
    }

    private CartDto toDto(Cart cart) {
        List<CartItemDto> items = cartItemRepository.findByCart_IdOrderByMenuItem_IdAsc(cart.getId()).stream()
                .map(item -> {
                    MenuItem menu = item.getMenuItem();
                    BigDecimal lineTotal = menu.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
                    return new CartItemDto(menu.getId(), menu.getName(), item.getQuantity(),
                            menu.getPrice(), lineTotal, menu.isAvailable());
                }).toList();
        BigDecimal total = items.stream().map(CartItemDto::getLineTotal)
                .reduce(new BigDecimal("0.00"), BigDecimal::add);
        return new CartDto(cart.getId(), items, total, "INR");
    }
}
