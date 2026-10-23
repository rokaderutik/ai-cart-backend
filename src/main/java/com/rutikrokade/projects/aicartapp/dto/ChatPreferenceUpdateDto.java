package com.rutikrokade.projects.aicartapp.dto;

import java.math.BigDecimal;
import java.util.*;
import lombok.Data;

@Data
public class ChatPreferenceUpdateDto {

    private BigDecimal maxPrice;

    private String priceComparison = "LTE";

    private List<String> dietaryTags;

    private List<String> categories;

    private Integer minSpiceLevel;

    private Integer maxSpiceLevel;

    private List<String> clearFields = new ArrayList<>();
}
