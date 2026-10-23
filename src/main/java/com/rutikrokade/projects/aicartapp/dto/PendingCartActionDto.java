package com.rutikrokade.projects.aicartapp.dto;

import lombok.Data;

@Data
public class PendingCartActionDto {

    private CartInstructionDto.Operation operation;

    private Integer amount;

    private Long itemId;
}
