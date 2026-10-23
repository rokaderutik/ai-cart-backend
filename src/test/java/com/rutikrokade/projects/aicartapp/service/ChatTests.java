package com.rutikrokade.projects.aicartapp.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.*;
import com.google.genai.Client;
import com.rutikrokade.projects.aicartapp.dto.*;
import com.rutikrokade.projects.aicartapp.exception.ApiException;
import com.rutikrokade.projects.aicartapp.repository.RetrievalRepository;
import com.rutikrokade.projects.aicartapp.util.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;

class ChatTests {

    private ChatTurnDto turn() {
        var turn = new ChatTurnDto();
        turn.setRequestId(UUID.randomUUID());
        turn.setContext(new ChatContextDto());
        turn.setHistory(List.of());
        turn.setCatalog(List.of(Map.of("id", 11L, "name", "Dal Tadka")));
        return turn;
    }

    private ChatPlanDto plan() {
        var plan = new ChatPlanDto();
        plan.setIntent(ChatPlanDto.Intent.KNOWLEDGE);
        plan.setTarget(RetrievalRequestDto.Target.MENU);
        plan.setMenuQuery("mild vegetarian food");
        return plan;
    }

    @Test void preferencesAreInheritedReplacedAndExplicitlyCleared() {
        var old = new RetrievalFiltersDto();
        old.setDietaryTags(List.of("VEGETARIAN"));
        old.setCategories(List.of("MAIN"));
        old.setMaxSpiceLevel(1);
        var update = new ChatPreferenceUpdateDto();
        update.setMaxPrice(new BigDecimal("300"));
        update.setPriceComparison("LT");
        update.setCategories(List.of("STARTER"));
        var result = ChatPlanValidation.mergedFilters(old, update);
        assertThat(result.getMaxPrice()).isEqualByComparingTo("299.99");
        assertThat(result.getDietaryTags()).containsExactly("VEGETARIAN");
        assertThat(result.getCategories()).containsExactly("STARTER");
        assertThat(result.getMaxSpiceLevel()).isEqualTo(1);
        assertThat(old.getMaxPrice()).isNull();
        update = new ChatPreferenceUpdateDto();
        update.setClearFields(List.of("maxPrice", "spiceLevel"));
        result = ChatPlanValidation.mergedFilters(result, update);
        assertThat(result.getMaxPrice()).isNull();
        assertThat(result.getMaxSpiceLevel()).isNull();
        assertThat(result.getDietaryTags()).containsExactly("VEGETARIAN");
    }

    @Test void rejectsUnknownTagsContradictionsInvalidBoundsAndInventedIds() {
        var turn = turn();
        var plan = plan();
        plan.getPreferences().setDietaryTags(List.of("GLUTEN_FREE"));
        assertThatThrownBy(() -> ChatPlanValidation.validate(plan, "food", turn)).isInstanceOf(RuntimeException.class);
        plan.getPreferences().setDietaryTags(List.of("VEGETARIAN", "NON_VEGETARIAN"));
        assertThatThrownBy(() -> ChatPlanValidation.validate(plan, "food", turn)).isInstanceOf(RuntimeException.class);
        plan.getPreferences().setDietaryTags(null);
        plan.getPreferences().setMinSpiceLevel(6);
        assertThatThrownBy(() -> ChatPlanValidation.validate(plan, "food", turn)).isInstanceOf(RuntimeException.class);
        plan.getPreferences().setMinSpiceLevel(null);
        plan.setItemIds(List.of(999L));
        assertThatThrownBy(() -> ChatPlanValidation.validate(plan, "food", turn)).isInstanceOf(RuntimeException.class);
        plan.setItemIds(List.of(11L));
        assertThatThrownBy(() -> ChatPlanValidation.validate(plan, "food", turn)).isInstanceOf(RuntimeException.class);
        ChatPlanValidation.validate(plan, "Tell me about Dal Tadka", turn);
    }

    @Test void ordinalReferencesPreserveDisplayedOrderAndMissingReferencesClarify() {
        var turn = turn();
        turn.getContext().setRecommendationIds(List.of(11L, 22L));
        var plan = plan();
        plan.setReferencePositions(List.of(2));
        ChatPlanValidation.validate(plan, "second one", turn);
        assertThat(ChatPlanValidation.references(plan, turn.getContext())).containsExactly(22L);
        plan.setReferencePositions(List.of(3));
        ChatPlanValidation.validate(plan, "third one", turn);
        assertThat(plan.getIntent()).isEqualTo(ChatPlanDto.Intent.CLARIFICATION);
    }

    @Test void allergiesRequireGuideAndRestaurantConfirmation() {
        var plan = plan();
        ChatPlanValidation.validate(plan, "I have a peanut allergy", turn());
        assertThat(plan.getTarget()).isEqualTo(RetrievalRequestDto.Target.BOTH);
        assertThat(plan.isRequiresRestaurantConfirmation()).isTrue();
        assertThat(plan.getGuideQuery()).contains("cross-contact");
        var turn = turn();
        turn.getContext().setRestaurantConfirmationRequired(true);
        var followup = plan();
        ChatPlanValidation.validate(followup, "more options please", turn);
        assertThat(followup.isRequiresRestaurantConfirmation()).isTrue();
        assertThat(followup.getTarget()).isEqualTo(RetrievalRequestDto.Target.BOTH);
    }

    private RetrievedDocumentDto document() {
        var menu = new RetrievalMenuItemDto(11L, "Dal", "Lentils", "MAIN", List.of("lentils"),
                List.of("VEGETARIAN"), 1, new BigDecimal("220"), "INR", true);
        return new RetrievedDocumentDto(UUID.randomUUID(), "menu:dal", "MENU", "Dal", .8, "Lentils", "Lentils", Map.of(), menu);
    }

    private ChatAnswerDraftDto draft() {
        var draft = new ChatAnswerDraftDto();
        var section = new ChatAnswerSectionDto();
        section.setText("A mild lentil dish.");
        section.setSourceIds(List.of("menu:dal"));
        draft.setSections(List.of(section));
        draft.setRecommendationIds(List.of(11L));
        return draft;
    }

    @Test void groundingRejectsForeignSourcesCardsNumericClaimsAndUnsafeRecommendations() {
        var plan = plan(); var draft = draft(); var evidence = List.of(document()); var filters = new RetrievalFiltersDto();
        ChatAnswerValidation.validate(draft, plan, evidence, filters);
        draft.getSections().getFirst().setSourceIds(List.of("guide:invented"));
        assertThatThrownBy(() -> ChatAnswerValidation.validate(draft, plan, evidence, filters)).isInstanceOf(IllegalArgumentException.class);
        draft.getSections().getFirst().setSourceIds(List.of("menu:dal"));
        draft.setRecommendationIds(List.of(99L));
        assertThatThrownBy(() -> ChatAnswerValidation.validate(draft, plan, evidence, filters)).isInstanceOf(IllegalArgumentException.class);
        draft.setRecommendationIds(List.of(11L));
        filters.setMaxPrice(new BigDecimal("100"));
        assertThatThrownBy(() -> ChatAnswerValidation.validate(draft, plan, evidence, filters)).isInstanceOf(IllegalArgumentException.class);
        filters.setMaxPrice(null);
        plan.setRequiresRestaurantConfirmation(true);
        assertThatThrownBy(() -> ChatAnswerValidation.validate(draft, plan, evidence, filters)).isInstanceOf(IllegalArgumentException.class);
        plan.setRequiresRestaurantConfirmation(false);
        draft.getSections().getFirst().setText("It costs 100 rupees.");
        assertThatThrownBy(() -> ChatAnswerValidation.validate(draft, plan, evidence, filters)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void oneRepairBudgetIsSharedAcrossBothModelStages() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(output("not json"), output(StructuredChatClient.JSON.writeValueAsString(plan())), output("not json"));
        var client = new StructuredChatClient(model, mock(Client.class), "test-model");
        var budget = new ChatCallBudget();
        client.generate("planner", Map.of(), ChatPlanDto.class, p -> {}, budget);
        assertThat(budget.getCalls()).isEqualTo(2);
        assertThat(budget.isRepaired()).isTrue();
        assertThatThrownBy(() -> client.generate("answer", Map.of(), ChatAnswerDraftDto.class, p -> {}, budget))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("CHAT_INVALID_MODEL_OUTPUT");
        verify(model, times(3)).call(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    @Test void providerFailureIsNotRetriedAsJsonRepair() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenThrow(new RuntimeException("secret provider detail"));
        var client = new StructuredChatClient(model, mock(Client.class), "test-model");
        var budget = new ChatCallBudget();
        assertThatThrownBy(() -> client.generate("planner", Map.of(), ChatPlanDto.class, p -> {}, budget))
                .isInstanceOf(ApiException.class).hasMessageNotContaining("secret").extracting("code").isEqualTo("CHAT_MODEL_UNAVAILABLE");
        assertThat(budget.isRepaired()).isFalse();
        verify(model).call(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    @Test void greetingAndCartActionsNeverRetrieveOrMutateAndReplaySkipsModels() {
        var persistence = mock(ChatPersistenceService.class);
        var planner = mock(QueryPlanningService.class);
        var answer = mock(GroundedAnswerService.class);
        var retrieval = mock(RetrievalService.class);
        var repository = mock(RetrievalRepository.class);
        var embedding = mock(RetrievalEmbeddingModel.class);
        var carts = mock(CartService.class);
        var service = new ChatServiceImpl(persistence, planner, answer, retrieval, repository, embedding, carts);
        var request = new ChatRequestDto(); request.setRequestId(UUID.randomUUID()); request.setMessage("add two");
        var turn = turn();
        when(persistence.begin(any(), any())).thenReturn(turn);
        var plan = plan(); plan.setIntent(ChatPlanDto.Intent.CART_ACTION); plan.setTarget(null);
        when(planner.plan(any(), any(), any(), any())).thenReturn(plan);
        when(carts.getCart("session")).thenReturn(new CartDto("cart", List.of(), BigDecimal.ZERO, "INR"));
        when(persistence.complete(any(), any(), any(), any())).thenAnswer(call -> call.getArgument(2));
        var result = service.chat("session", request);
        assertThat(result.getOutcome()).isEqualTo("CART_ACTION_UNAVAILABLE");
        verifyNoInteractions(answer, retrieval, repository, embedding);
        verify(carts).getCart("session"); verifyNoMoreInteractions(carts);
        turn.setReplay(result);
        assertThat(service.chat("session", request)).isSameAs(result);
        verify(planner).plan(any(), any(), any(), any());
    }

    @Test void requestSchemaRejectsFrontendRoutingFieldsAndDuplicateJsonKeys() {
        assertThatThrownBy(() -> StructuredChatClient.JSON.readValue(
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"message\":\"hi\",\"target\":\"MENU\"}", ChatRequestDto.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> StructuredChatClient.JSON.readValue("{\"message\":\"a\",\"message\":\"b\"}", ChatRequestDto.class))
                .isInstanceOf(RuntimeException.class);
    }

    @Test void noEligibleItemsKeepsConstraintsAndClearsPreviousRecommendations() {
        var fixture = new Flow();
        fixture.turn.getContext().setRecommendationIds(List.of(11L));
        fixture.turn.getContext().getPreferences().setDietaryTags(List.of("VEGETARIAN"));
        fixture.plan.getPreferences().setMaxPrice(new BigDecimal("1"));
        var found = new RetrievalResponseDto();
        found.setMenu(new RetrievalBranchDto(RetrievalBranchDto.Outcome.NO_ELIGIBLE_ITEMS, 0, .69, List.of()));
        when(fixture.retrieval.retrieve(any())).thenReturn(found);
        var response = fixture.run();
        assertThat(response.getOutcome()).isEqualTo("NO_ELIGIBLE_ITEMS");
        assertThat(fixture.saved.getRecommendationIds()).isEmpty();
        assertThat(fixture.saved.getPreferences().getMaxPrice()).isEqualByComparingTo("1");
        assertThat(fixture.saved.getPreferences().getDietaryTags()).containsExactly("VEGETARIAN");
        verifyNoInteractions(fixture.answers);
    }

    @Test void partialBothAnswerUsesOnlyEvidenceAndServerFacts() {
        var fixture = new Flow();
        fixture.plan.setTarget(RetrievalRequestDto.Target.BOTH);
        fixture.plan.setGuideQuery("takeaway");
        var found = new RetrievalResponseDto();
        found.setMenu(new RetrievalBranchDto(RetrievalBranchDto.Outcome.EVIDENCE_FOUND, 1, .69, List.of(document())));
        found.setGuide(new RetrievalBranchDto(RetrievalBranchDto.Outcome.NO_RELEVANT_EVIDENCE, 10, .61, List.of()));
        when(fixture.retrieval.retrieve(any())).thenReturn(found);
        when(fixture.answers.answer(any(), any(), any(), any(), any(), any(), any())).thenReturn(draft());
        var result = fixture.run();
        assertThat(result.getOutcome()).isEqualTo("PARTIAL_EVIDENCE");
        assertThat(result.getReply()).contains("INR 220", "spice 1/5", "couldn't find supporting restaurant guide");
        assertThat(result.getRecommendations()).extracting(RetrievalMenuItemDto::getId).containsExactly(11L);
        assertThat(fixture.saved.getRecommendationIds()).containsExactly(11L);
    }

    @Test void referencedDetailsBypassEmbeddingAndNeverSubstituteMissingItems() {
        var fixture = new Flow();
        fixture.turn.getContext().setRecommendationIds(List.of(11L, 22L));
        fixture.plan.setOperation(ChatPlanDto.Operation.DETAILS);
        fixture.plan.setReferencePositions(List.of(1));
        when(fixture.repository.referencedMenu(any(), any())).thenReturn(List.of(document()));
        var draft = draft(); draft.setRecommendationIds(List.of());
        when(fixture.answers.answer(any(), any(), any(), any(), any(), any(), any())).thenReturn(draft);
        var result = fixture.run();
        assertThat(result.getDiagnostics().get("embeddingCalls")).isEqualTo(0);
        verifyNoInteractions(fixture.retrieval);
        assertThat(fixture.saved.getRecommendationIds()).containsExactly(11L, 22L);
        when(fixture.repository.referencedMenu(any(), any())).thenReturn(List.of());
        result = fixture.run();
        assertThat(result.getOutcome()).isEqualTo("NO_EVIDENCE");
        assertThat(result.getReply()).contains("not substituted");
        verify(fixture.answers).answer(any(), any(), any(), any(), any(), any(), any());
    }

    @Test void guideTurnsPreservePreferencesAndOrdering() {
        var fixture = new Flow();
        fixture.turn.getContext().setRecommendationIds(List.of(22L, 11L));
        fixture.turn.getContext().getPreferences().setCategories(List.of("MAIN"));
        fixture.plan.setTarget(RetrievalRequestDto.Target.GUIDE);
        fixture.plan.setGuideQuery("opening hours");
        var found = new RetrievalResponseDto();
        found.setGuide(new RetrievalBranchDto(RetrievalBranchDto.Outcome.NO_RELEVANT_EVIDENCE, 10, .61, List.of()));
        when(fixture.retrieval.retrieve(any())).thenReturn(found);
        fixture.run();
        assertThat(fixture.saved.getRecommendationIds()).containsExactly(22L, 11L);
        assertThat(fixture.saved.getPreferences().getCategories()).containsExactly("MAIN");
    }

    @Test void providerFailureMarksAttemptFailedWithoutCompletingOrUpdatingContext() {
        var fixture = new Flow();
        when(fixture.planner.plan(any(), any(), any(), any())).thenThrow(
                new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "CHAT_MODEL_UNAVAILABLE", "Unavailable"));
        assertThatThrownBy(fixture::run).isInstanceOf(ApiException.class);
        verify(fixture.persistence).fail(eq("session"), eq(fixture.turn), eq("CHAT_MODEL_UNAVAILABLE"));
        verify(fixture.persistence, never()).complete(any(), any(), any(), any());
        assertThat(fixture.saved).isNull();
    }

    private class Flow {
        final ChatPersistenceService persistence = mock(ChatPersistenceService.class);
        final QueryPlanningService planner = mock(QueryPlanningService.class);
        final GroundedAnswerService answers = mock(GroundedAnswerService.class);
        final RetrievalService retrieval = mock(RetrievalService.class);
        final RetrievalRepository repository = mock(RetrievalRepository.class);
        final RetrievalEmbeddingModel embedding = mock(RetrievalEmbeddingModel.class);
        final CartService carts = mock(CartService.class);
        final ChatTurnDto turn = turn();
        final ChatPlanDto plan = plan();
        ChatContextDto saved;
        final ChatService service = new ChatServiceImpl(persistence, planner, answers, retrieval, repository, embedding, carts);

        Flow() {
            when(persistence.begin(any(), any())).thenReturn(turn);
            when(planner.plan(any(), any(), any(), any())).thenReturn(plan);
            when(carts.getCart(any())).thenReturn(new CartDto("cart", List.of(), BigDecimal.ZERO, "INR"));
            when(embedding.fingerprint()).thenReturn("test");
            when(persistence.complete(any(), any(), any(), any())).thenAnswer(call -> {
                saved = call.getArgument(3); return call.getArgument(2);
            });
        }

        ChatResponseDto run() {
            var request = new ChatRequestDto(); request.setRequestId(UUID.randomUUID()); request.setMessage("test question");
            return service.chat("session", request);
        }
    }

    private static ChatResponse output(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
