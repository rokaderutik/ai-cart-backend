package com.rutikrokade.projects.aicartapp.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.Value;

@Value
public class RetrievalMenuItemDto {

    Long id;

    String name;

    String description;

    String category;

    List<String> ingredients;

    List<String> dietaryTags;

    int spiceLevel;

    BigDecimal price;

    String currency;

    boolean available;
}
