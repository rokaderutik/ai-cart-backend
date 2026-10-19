package com.rutikrokade.projects.aicartapp.controller;

import com.rutikrokade.projects.aicartapp.dto.CartDto;
import com.rutikrokade.projects.aicartapp.dto.CartItemRequestDto;
import com.rutikrokade.projects.aicartapp.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    public CartDto getCart(@RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return cartService.getCart(sessionId);
    }

    @PutMapping("/items/{itemId}")
    public CartDto setItemQuantity(@RequestHeader(value = "X-Session-Id", required = false) String sessionId,
                                   @PathVariable Long itemId, @Valid @RequestBody CartItemRequestDto request) {
        return cartService.setItemQuantity(sessionId, itemId, request.getQuantity());
    }
}
