package com.rutikrokade.projects.aicartapp.dto;

import java.util.*;
import lombok.Data;

@Data
public class ChatPlanDto {

    public enum Intent { KNOWLEDGE, CLARIFICATION, GREETING, OUT_OF_SCOPE, CART_READ, CART_ACTION }

    public enum Operation { RECOMMEND, DETAILS, COMPARE }

    private Intent intent;

    private RetrievalRequestDto.Target target;

    private Operation operation = Operation.RECOMMEND;

    private String menuQuery;

    private String guideQuery;

    private ChatPreferenceUpdateDto preferences = new ChatPreferenceUpdateDto();

    private List<Long> itemIds = new ArrayList<>();

    private List<Integer> referencePositions = new ArrayList<>();

    private boolean usePreviousRecommendations;

    private boolean requiresRestaurantConfirmation;

    private String clarification;

    private String unsupportedPart;

    private CartInstructionDto cartAction;
}
