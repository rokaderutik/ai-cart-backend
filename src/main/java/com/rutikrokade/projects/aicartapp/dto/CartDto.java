package com.rutikrokade.projects.aicartapp.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CartDto {

    private String cartId;

    private List<CartItemDto> items;

    private BigDecimal total;

    private String currency;
}
