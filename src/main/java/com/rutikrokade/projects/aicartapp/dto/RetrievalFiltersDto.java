package com.rutikrokade.projects.aicartapp.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

@Data
public class RetrievalFiltersDto {

    private BigDecimal maxPrice;

    private List<String> dietaryTags = List.of();

    private List<String> categories = List.of();

    private Integer minSpiceLevel;

    private Integer maxSpiceLevel;

    private Boolean availableOnly = true;
}
