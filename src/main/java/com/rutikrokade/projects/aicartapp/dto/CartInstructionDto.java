package com.rutikrokade.projects.aicartapp.dto;

import lombok.Data;

@Data
public class CartInstructionDto {

    public enum Operation { ADD, SET, DECREASE, REMOVE, CHECKOUT }

    public enum Reference { EXPLICIT, LAST_CART, SOLE_CART, PENDING, NONE }

    private Operation operation;

    private Integer amount;

    private Reference reference = Reference.NONE;

    private boolean multipleItems;
}
