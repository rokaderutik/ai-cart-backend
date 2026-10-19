package com.rutikrokade.projects.aicartapp.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import com.rutikrokade.projects.aicartapp.dto.CartDto;
import com.rutikrokade.projects.aicartapp.dto.CartItemDto;
import com.rutikrokade.projects.aicartapp.exception.ApiException;
import com.rutikrokade.projects.aicartapp.service.CartService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = CartController.class, properties = "app.cors.allowed-origins=http://localhost:5173")
@ActiveProfiles("test")
class CartControllerTests {

    private static final String SESSION_ID = "SESSION_00000000-0000-4000-8000-000000000001";
    private static final Long ITEM_ID = 1L;
    private static final CartDto EMPTY_CART = new CartDto(
            "CART_00000000-0000-4000-8000-000000000001", List.of(), new BigDecimal("0.00"), "INR");

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private CartService cartService;

    @Test
    void wrapsCartExactlyOnce() throws Exception {
        when(cartService.getCart(SESSION_ID)).thenReturn(EMPTY_CART);
        mockMvc.perform(get("/cart").header("X-Session-Id", SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.data.cartId").value(EMPTY_CART.getCartId()))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.data").doesNotExist())
                .andExpect(jsonPath("$.error").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 20})
    void acceptsQuantityBoundaries(int quantity) throws Exception {
        when(cartService.setItemQuantity(SESSION_ID, ITEM_ID, quantity)).thenReturn(EMPTY_CART);
        mockMvc.perform(put("/cart/items/" + ITEM_ID).header("X-Session-Id", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":" + quantity + "}"))
                .andExpect(status().isOk());
        verify(cartService).setItemQuantity(SESSION_ID, ITEM_ID, quantity);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"quantity\":null}", "{\"quantity\":-1}", "{\"quantity\":21}"})
    void rejectsMissingOrOutOfRangeQuantities(String json) throws Exception {
        mockMvc.perform(put("/cart/items/" + ITEM_ID).header("X-Session-Id", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.errors[0].field").value("quantity"));
        verifyNoInteractions(cartService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"quantity\":1.5}", "{\"quantity\":2.0}", "{\"quantity\":\"2\"}",
            "{\"quantity\":true}", "{\"quantity\":2147483648}", "{", "null"})
    void rejectsMalformedOrCoercedQuantities(String json) throws Exception {
        mockMvc.perform(put("/cart/items/" + ITEM_ID).header("X-Session-Id", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(cartService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-number", "1.5", "9223372036854775808", "00000000-0000-4000-8000-000000000001"})
    void rejectsMalformedMenuId(String itemId) throws Exception {
        mockMvc.perform(put("/cart/items/" + itemId).header("X-Session-Id", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.status").value(400));
        verifyNoInteractions(cartService);
    }

    @Test
    void returnsNumericMenuItemIds() throws Exception {
        CartItemDto item = new CartItemDto(ITEM_ID, "Paneer Butter Masala", 2,
                new BigDecimal("250.00"), new BigDecimal("500.00"), true);
        when(cartService.setItemQuantity(SESSION_ID, ITEM_ID, 2)).thenReturn(new CartDto(
                EMPTY_CART.getCartId(), List.of(item), new BigDecimal("500.00"), "INR"));
        mockMvc.perform(put("/cart/items/1").header("X-Session-Id", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].itemId").isNumber())
                .andExpect(jsonPath("$.data.items[0].itemId").value(1))
                .andExpect(jsonPath("$.data.total").value(500.00));
    }

    @Test
    void passesMissingHeaderToCentralSessionValidation() throws Exception {
        when(cartService.getCart(null)).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED,
                "INVALID_SESSION", "A valid X-Session-Id header is required"));
        mockMvc.perform(get("/cart"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error.code").value("INVALID_SESSION"));
    }

    @Test
    void preservesDomainErrorStatus() throws Exception {
        when(cartService.setItemQuantity(SESSION_ID, ITEM_ID, 1)).thenThrow(new ApiException(
                HttpStatus.CONFLICT, "ITEM_UNAVAILABLE", "Menu item is currently unavailable"));
        mockMvc.perform(put("/cart/items/" + ITEM_ID).header("X-Session-Id", SESSION_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ITEM_UNAVAILABLE"));
    }

    @Test
    void preservesUnsupportedMethodAndMediaTypeStatuses() throws Exception {
        mockMvc.perform(put("/cart"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.status").value(405));
        mockMvc.perform(put("/cart/items/" + ITEM_ID).contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.status").value(415));
    }

    @Test
    void wrapsUnknownRouteErrors() throws Exception {
        mockMvc.perform(get("/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
