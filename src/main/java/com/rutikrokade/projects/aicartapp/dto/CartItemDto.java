package com.rutikrokade.projects.aicartapp.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CartItemDto {

    private Long itemId;

    private String name;

    private int quantity;

    private BigDecimal unitPrice;

    private BigDecimal lineTotal;

    private boolean available;
}
