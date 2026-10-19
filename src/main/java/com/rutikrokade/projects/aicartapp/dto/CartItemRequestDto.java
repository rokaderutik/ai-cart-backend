package com.rutikrokade.projects.aicartapp.dto;

import com.rutikrokade.projects.aicartapp.util.StrictIntegerDeserializer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.annotation.JsonDeserialize;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CartItemRequestDto {

    @NotNull(message = "Quantity is required")
    @Min(value = 0, message = "Quantity must be between 0 and 20")
    @Max(value = 20, message = "Quantity must be between 0 and 20")
    @JsonDeserialize(using = StrictIntegerDeserializer.class)
    private Integer quantity;
}
