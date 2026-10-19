package com.rutikrokade.projects.aicartapp.service;

import com.rutikrokade.projects.aicartapp.dto.CartDto;

public interface CartService {

    CartDto getCart(String sessionId);

    CartDto setItemQuantity(String sessionId, Long itemId, Integer quantity);
}
