package com.rutikrokade.projects.aicartapp.dto;

import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class SourceMenuItemDto {

    @NotBlank
    @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*")
    @Size(max = 120)
    private String sourceKey;

    @NotBlank
    @Size(max = 120)
    private String name;

    @NotBlank
    @Size(max = 5000)
    private String description;

    @NotBlank
    @Size(max = 60)
    private String category;

    @NotEmpty
    @Size(max = 40)
    private List<@NotBlank @Size(max = 80) String> ingredients;

    @NotNull
    @Size(max = 10)
    private List<@Pattern(regexp = "VEGETARIAN|VEGAN|NON_VEGETARIAN") String> dietaryTags;

    @NotNull
    @Min(0)
    @Max(5)
    private Integer spiceLevel;

    @NotNull
    @DecimalMin("0.00")
    @Digits(integer = 8, fraction = 2)
    private BigDecimal price;

    @NotNull
    @Pattern(regexp = "INR")
    private String currency;

    @NotNull
    private Boolean available;
}
