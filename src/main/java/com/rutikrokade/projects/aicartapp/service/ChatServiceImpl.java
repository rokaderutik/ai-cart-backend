package com.rutikrokade.projects.aicartapp.service;

import java.util.*;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.exception.ApiException;
import com.rutikrokade.projects.aicartapp.repository.RetrievalRepository;
import com.rutikrokade.projects.aicartapp.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@Profile({"dev", "prod"})
@RequiredArgsConstructor
@Slf4j
public class ChatServiceImpl implements ChatService {

    private final ChatPersistenceService persistence;
    private final QueryPlanningService planner;
    private final GroundedAnswerService answers;
    private final RetrievalService retrieval;
    private final RetrievalRepository repository;
    private final RetrievalEmbeddingModel embedding;
    private final CartService carts;

    private final CartActionService cartActions;

    @Override
    public ChatResponseDto chat(String sessionId, ChatRequestDto request) {
        validate(request);
        var normalized = new ChatRequestDto();
        normalized.setRequestId(request.getRequestId());
        normalized.setMessage(request.getMessage().strip());
        long start = System.nanoTime();
        ChatTurnDto turn = persistence.begin(sessionId, normalized);
        if (turn.getReplay() != null) return turn.getReplay();
        try {
            var budget = new ChatCallBudget();
            var response = new ChatResponseDto();
            response.setRequestId(normalized.getRequestId());
            CartDto cart = carts.getCart(sessionId);
            response.setCart(cart);
            long stage = System.nanoTime();
            ChatPlanDto plan = planner.plan(normalized.getMessage(), turn, cart, budget);
            response.getTimings().put("planningMillis", millis(stage));
            var context = StructuredChatClient.JSON.convertValue(turn.getContext(), ChatContextDto.class);
            context.setVersion(2);
            response.getDiagnostics().put("intent", plan.getIntent());
            response.getDiagnostics().put("target", plan.getTarget());
            response.getDiagnostics().put("promptVersion", StructuredChatClient.VERSION);
            if (plan.getIntent() == ChatPlanDto.Intent.CART_ACTION) {
                return cartActions.handle(sessionId, normalized.getMessage(), plan, turn, cart, response, context, budget, start);
            }
            context.setPendingCartAction(null);
            if ((plan.getIntent() == ChatPlanDto.Intent.KNOWLEDGE && plan.getTarget() != RetrievalRequestDto.Target.GUIDE)
                    || plan.getIntent() == ChatPlanDto.Intent.CLARIFICATION) context.setLastCartItemId(null);
            switch (plan.getIntent()) {
                case GREETING -> simple(response, "GREETING", "Hello! I can help you explore our menu and restaurant information.");
                case OUT_OF_SCOPE -> simple(response, "OUT_OF_SCOPE", "I can help with this restaurant's menu, policies and your cart. Please ask a restaurant-related question.");
                case CART_ACTION -> throw new IllegalStateException("Cart action must use the transactional handler");
                case CART_READ -> simple(response, "CART", cart.getItems().isEmpty() ? "Your cart is empty."
                        : "Your current cart contains " + cart.getItems().size() + " item types. Total: " + cart.getCurrency() + " " + cart.getTotal().toPlainString() + ".");
                case CLARIFICATION -> {
                    simple(response, "CLARIFICATION", plan.getClarification());
                    context.setPendingClarification(plan.getClarification());
                    // A clarification is not consent to change hard constraints.
                }
                case KNOWLEDGE -> {
                    stage = System.nanoTime();
                    knowledge(normalized.getMessage(), plan, turn, cart, context, response, budget);
                    response.getTimings().put("knowledgeMillis", millis(stage));
                }
            }
            if (plan.getUnsupportedPart() != null && !plan.getUnsupportedPart().isBlank()
                    && plan.getIntent() == ChatPlanDto.Intent.KNOWLEDGE) {
                section(response, "I can only answer the restaurant-related part of that question.", List.of());
            }
            ChatResponses.finish(response, budget, start);
            log.info("Chat request {} outcome={} sources={} modelCalls={} elapsedMs={}", request.getRequestId(),
                    response.getOutcome(), response.getSources().stream().map(ChatSourceDto::getSourceId).toList(), budget.getCalls(), millis(start));
            return persistence.complete(sessionId, turn, response, context);
        } catch (RuntimeException failure) {
            String code = failure instanceof ApiException api ? api.getCode() : "CHAT_UNAVAILABLE";
            try { persistence.fail(sessionId, turn, code); }
            catch (RuntimeException markerFailure) {
                log.warn("Chat request {} failure marker unavailable; lease will recover it", request.getRequestId());
            }
            if (failure instanceof ApiException api) throw api;
            log.warn("Chat request {} failed type={}", request.getRequestId(), failure.getClass().getSimpleName());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, "Chat is temporarily unavailable; retry this request");
        }
    }

    private void knowledge(String message, ChatPlanDto plan, ChatTurnDto turn, CartDto cart,
                           ChatContextDto context, ChatResponseDto response, ChatCallBudget budget) {
        RetrievalFiltersDto filters = ChatPlanValidation.mergedFilters(context.getPreferences(), plan.getPreferences());
        List<Long> references = ChatPlanValidation.references(plan, context);
        var documents = new ArrayList<RetrievedDocumentDto>();
        boolean menuMissing = false;
        boolean guideMissing = false;
        boolean referencesMissing = false;
        boolean noEligible = false;
        int embeddingCalls = 0;
        if (!references.isEmpty() && plan.getTarget() != RetrievalRequestDto.Target.GUIDE) {
            var exact = repository.referencedMenu(references, embedding.fingerprint());
            referencesMissing = exact.size() != references.size();
            documents.addAll(plan.getOperation() == ChatPlanDto.Operation.RECOMMEND
                    ? exact.stream().filter(doc -> ChatPlanValidation.eligible(doc.getMenuItem(), filters)).toList() : exact);
            menuMissing = documents.isEmpty();
            noEligible = menuMissing && !referencesMissing;
            response.getDiagnostics().put("exactItemLookup", true);
        }
        if (references.isEmpty() || plan.getTarget() == RetrievalRequestDto.Target.GUIDE
                || plan.getTarget() == RetrievalRequestDto.Target.BOTH) {
            RetrievalRequestDto request = new RetrievalRequestDto();
            request.setTarget(!references.isEmpty() ? RetrievalRequestDto.Target.GUIDE : plan.getTarget());
            request.setQuery(request.getTarget() == RetrievalRequestDto.Target.GUIDE ? plan.getGuideQuery() : plan.getMenuQuery());
            if (request.getTarget() == RetrievalRequestDto.Target.BOTH) {
                request.setMenuQuery(plan.getMenuQuery());
                request.setGuideQuery(plan.getGuideQuery());
            }
            if (request.getTarget() != RetrievalRequestDto.Target.GUIDE) request.setFilters(filters);
            var found = retrieval.retrieve(request);
            response.getDiagnostics().put("corpusRevision", found.getCorpusRevision());
            response.getDiagnostics().put("embeddingFingerprint", found.getEmbeddingFingerprint());
            embeddingCalls = found.getEmbeddingCalls();
            if (found.getMenu() != null) {
                documents.addAll(found.getMenu().getDocuments());
                menuMissing = found.getMenu().getDocuments().isEmpty();
                noEligible = found.getMenu().getOutcome() == RetrievalBranchDto.Outcome.NO_ELIGIBLE_ITEMS;
            }
            if (found.getGuide() != null) {
                documents.addAll(found.getGuide().getDocuments());
                guideMissing = found.getGuide().getDocuments().isEmpty();
            }
        }
        response.getDiagnostics().put("embeddingCalls", embeddingCalls);
        response.getDiagnostics().put("appliedFilters", filters);
        response.setSources(documents.stream().map(doc -> {
            var source = new ChatSourceDto();
            source.setSourceId(doc.getSourceId()); source.setTitle(doc.getTitle()); source.setSourceType(doc.getSourceType());
            source.setContent(doc.getContent()); source.setSnippet(doc.getSnippet());
            return source;
        }).toList());
        if (documents.isEmpty()) {
            simple(response, noEligible ? "NO_ELIGIBLE_ITEMS" : "NO_EVIDENCE", noEligible
                    ? "No available menu items match all your current preferences. Would you like to change a preference?"
                    : "I couldn't find supporting restaurant information for that question. Please try a more specific question or check with the restaurant.");
        } else {
            ChatAnswerDraftDto answer = answers.answer(message, plan, turn, cart, documents, filters, budget);
            response.setSections(new ArrayList<>(answer.getSections()));
            response.setRecommendations(answer.getRecommendationIds().stream().map(id -> documents.stream()
                    .map(RetrievedDocumentDto::getMenuItem).filter(Objects::nonNull)
                    .filter(item -> item.getId().equals(id)).findFirst().orElseThrow()).toList());
            response.setOutcome(answer.isInformationMissing() ? "INFORMATION_MISSING"
                    : menuMissing || guideMissing || referencesMissing ? "PARTIAL_EVIDENCE" : "ANSWERED");
            if (menuMissing) section(response, noEligible
                    ? "No available menu items match all your current preferences."
                    : "I couldn't find supporting menu information for that part of your question.", List.of());
            if (guideMissing) section(response, "I couldn't find supporting restaurant guide information for that part of your question.", List.of());
            appendFacts(response, plan, documents);
        }
        if (referencesMissing) section(response, "One or more referenced menu items are no longer available in the current published menu. I have not substituted other items.", List.of());
        if (plan.isRequiresRestaurantConfirmation()) {
            section(response, "Please confirm ingredients and cross-contact precautions directly with restaurant staff. I cannot verify that a dish is safe for an allergy.", List.of());
            response.setOutcome("RESTAURANT_CONFIRMATION_REQUIRED");
        }
        // Persist only successful knowledge turns; provider/validation failures roll back by never reaching completion.
        if (plan.getTarget() != RetrievalRequestDto.Target.GUIDE) context.setPreferences(filters);
        context.setRestaurantConfirmationRequired(plan.isRequiresRestaurantConfirmation()
                || context.isRestaurantConfirmationRequired() && !plan.getPreferences().getClearFields().contains("restaurantConfirmation"));
        context.setPendingClarification(null);
        if (plan.getOperation() == ChatPlanDto.Operation.RECOMMEND && plan.getTarget() != RetrievalRequestDto.Target.GUIDE) {
            context.setRecommendationIds(response.getRecommendations().stream().map(RetrievalMenuItemDto::getId).toList());
            if (response.getRecommendations().isEmpty()) context.setRecommendationMessageId(null);
        }
    }

    private static void appendFacts(ChatResponseDto response, ChatPlanDto plan, List<RetrievedDocumentDto> documents) {
        var facts = documents.stream().filter(doc -> doc.getMenuItem() != null)
                .filter(doc -> plan.getOperation() != ChatPlanDto.Operation.RECOMMEND
                        || response.getRecommendations().stream().anyMatch(item -> item.getId().equals(doc.getMenuItem().getId()))).toList();
        for (var doc : facts) {
            var item = doc.getMenuItem();
            section(response, item.getName() + " — " + item.getCurrency() + " " + item.getPrice().toPlainString()
                    + "; spice " + item.getSpiceLevel() + "/5; " + (item.isAvailable() ? "currently available." : "currently unavailable."),
                    List.of(doc.getSourceId()));
        }
        if (plan.getOperation() == ChatPlanDto.Operation.COMPARE && facts.size() >= 2) {
            var cheapest = facts.stream().min(Comparator.comparing(doc -> doc.getMenuItem().getPrice())).orElseThrow();
            var mildest = facts.stream().min(Comparator.comparingInt(doc -> doc.getMenuItem().getSpiceLevel())).orElseThrow();
            section(response, "Among these items, " + cheapest.getMenuItem().getName() + " has the lowest listed price"
                    + " and " + mildest.getMenuItem().getName() + " has the lowest spice level (ties are possible).",
                    facts.stream().map(RetrievedDocumentDto::getSourceId).toList());
        }
    }

    private static void simple(ChatResponseDto response, String outcome, String text) {
        response.setOutcome(outcome);
        section(response, text, List.of());
    }

    private static void section(ChatResponseDto response, String text, List<String> sources) {
        var section = new ChatAnswerSectionDto();
        section.setText(text); section.setSourceIds(sources);
        response.getSections().add(section);
    }

    private static long millis(long start) { return (System.nanoTime() - start) / 1_000_000; }

    private static void validate(ChatRequestDto request) {
        if (request == null || request.getRequestId() == null || request.getMessage() == null
                || request.getMessage().isBlank() || request.getMessage().strip().length() > 2000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "requestId (UUID) and message (1-2000 characters) are required");
        }
    }
}
