package com.rutikrokade.projects.aicartapp.dto;

import lombok.Data;

@Data
public class CartActionResultDto {

    private CartInstructionDto.Operation operation;

    private Long itemId;

    private Integer previousQuantity;

    private Integer quantity;

    private String outcome;

    private String reason;
}
