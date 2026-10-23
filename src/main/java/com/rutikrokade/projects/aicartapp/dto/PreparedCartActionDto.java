package com.rutikrokade.projects.aicartapp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PreparedCartActionDto {

    private CartInstructionDto.Operation operation;

    private Long itemId;

    private Integer expectedQuantity;

    private Integer quantity;
}
