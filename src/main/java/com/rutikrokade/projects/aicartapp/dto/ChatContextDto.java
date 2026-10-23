package com.rutikrokade.projects.aicartapp.dto;

import java.util.*;
import lombok.Data;

@Data
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class ChatContextDto {

    private RetrievalFiltersDto preferences = new RetrievalFiltersDto();

    private List<Long> recommendationIds = new ArrayList<>();

    private Long recommendationMessageId;

    private String pendingClarification;

    private boolean restaurantConfirmationRequired;

    private Long lastCartItemId;

    private PendingCartActionDto pendingCartAction;

    private int version = 2;
}
