package com.rutikrokade.projects.aicartapp.util;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.rutikrokade.projects.aicartapp.dto.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.core.io.*;
import tools.jackson.databind.json.JsonMapper;

class KnowledgeSourceReaderTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static jakarta.validation.ValidatorFactory factory;
    private static KnowledgeSourceReader reader;

    @BeforeAll
    static void setup() {
        factory = Validation.buildDefaultValidatorFactory();
        reader = new KnowledgeSourceReader(factory.getValidator());
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    static Resource text(String value) {
        return new ByteArrayResource(value.getBytes(StandardCharsets.UTF_8));
    }

    private KnowledgeSourceDto bundled() {
        return reader.read(new ClassPathResource("knowledge/menu.json"), new ClassPathResource("knowledge/restaurant-guide.md"));
    }

    @Test
    void bundledSourcesHaveFortyCompleteDishesAndTenIdentifiedGuideTopics() {
        var source = bundled();
        assertThat(source.getMenu()).hasSize(40);
        assertThat(source.getDocuments()).hasSize(50);
        assertThat(source.getDocuments()).extracting(KnowledgeDocumentDto::getId).doesNotHaveDuplicates();
        assertThat(source.getMenu()).filteredOn(i -> i.getSourceKey().equals("gulab-jamun"))
                .allMatch(i -> !i.getAvailable());
        assertThat(source.getDocuments()).filteredOn(d -> d.getSourceType().equals("MENU"))
                .allMatch(d -> !d.getContent().contains("Price:") && !d.getContent().contains("Available:"));
    }

    @Test
    void sourceOrderingFormattingAndArrayOrderingDoNotChangeHashes() {
        var first = bundled();
        List<SourceMenuItemDto> menu = new ArrayList<>(first.getMenu());
        Collections.reverse(menu);
        menu.forEach(i -> {
            var ingredients = new ArrayList<>(i.getIngredients());
            Collections.reverse(ingredients);
            i.setIngredients(ingredients);
        });
        var second = reader.read(text(JSON.writeValueAsString(menu)),
                new ClassPathResource("knowledge/restaurant-guide.md"));
        assertThat(second.getSourceHash()).isEqualTo(first.getSourceHash());
        assertThat(second.getDocuments()).isEqualTo(first.getDocuments());
    }

    @Test
    void priceChangeDoesNotChangeDocumentIdentityOrContentButChangesSourceHash() {
        var first = bundled();
        first.getMenu().getFirst().setPrice(new java.math.BigDecimal("123.45"));
        var second = reader.read(text(JSON.writeValueAsString(first.getMenu())),
                new ClassPathResource("knowledge/restaurant-guide.md"));
        assertThat(second.getSourceHash()).isNotEqualTo(first.getSourceHash());
        assertThat(second.getDocuments()).isEqualTo(first.getDocuments());
    }

    @Test
    void renameKeepsIdentityAndChangesContentHash() {
        var item = bundled().getMenu().getFirst();
        var old = KnowledgeDocuments.menu(item, "menu.json");
        item.setName("Updated dish name");
        var changed = KnowledgeDocuments.menu(item, "menu.json");
        assertThat(changed.getId()).isEqualTo(old.getId());
        assertThat(changed.getContentHash()).isNotEqualTo(old.getContentHash());
    }

    @Test
    void rejectsDuplicatesInvalidSpiceAndContradictoryTags() {
        var item = bundled().getMenu().getFirst();
        Resource guide = text("## Topic {#topic}\nA valid policy.");
        assertThatThrownBy(() -> reader.read(text(JSON.writeValueAsString(List.of(item, item))), guide))
                .hasMessageContaining("Duplicate");
        item.setSpiceLevel(6);
        assertThatThrownBy(() -> reader.read(text(JSON.writeValueAsString(List.of(item))), guide))
                .hasMessageContaining("spiceLevel");
        item.setSpiceLevel(1);
        item.setDietaryTags(List.of("VEGAN", "NON_VEGETARIAN"));
        assertThatThrownBy(() -> reader.read(text(JSON.writeValueAsString(List.of(item))), guide))
                .hasMessageContaining("Contradictory");
    }

    @Test
    void rejectsEmptySourcesMalformedTypesAndUnknownProperties() {
        Resource guide = text("## Topic {#topic}\nA valid policy.");
        assertThatThrownBy(() -> reader.read(text("[]"), guide)).hasMessageContaining("at least one");
        String item = JSON.writeValueAsString(List.of(bundled().getMenu().getFirst()));
        assertThatThrownBy(() -> reader.read(text(item.replace("\"available\":true", "\"available\":\"true\"")), guide))
                .hasMessageContaining("Invalid menu JSON");
        assertThatThrownBy(() -> reader.read(text(item.replace("\"sourceKey\"", "\"unknownField\"")), guide))
                .hasMessageContaining("Invalid menu JSON");
        assertThatThrownBy(() -> reader.read(text(item), text("# Empty guide"))).hasMessageContaining("at least one");
    }

    @Test
    void rejectsMissingDuplicateAndOversizedGuideTopics() {
        Resource menu = new ClassPathResource("knowledge/menu.json");
        for (String guide : List.of("## Missing identifier\nPolicy", "## A {#same}\nOne\n## B {#same}\nTwo")) {
            assertThatThrownBy(() -> reader.read(menu, text(guide))).hasMessageContaining("unique stable");
        }
        assertThatThrownBy(() -> reader.read(menu, text("## Long {#long}\n" + "many words ".repeat(700))))
                .hasMessageContaining("600 estimated tokens");
        assertThatThrownBy(() -> reader.read(menu, text("## Empty {#empty}")))
                .hasMessageContaining("Empty guide topic");
    }

    @Test
    void guideHeadingRenameKeepsStableVectorId() {
        Resource menu = new ClassPathResource("knowledge/menu.json");
        var first = reader.read(menu, text("## Original {#stable}\nKeep the entire policy."));
        var second = reader.read(menu, text("## Renamed {#stable}\nKeep the entire policy."));
        var a = first.getDocuments().stream().filter(d -> d.getSourceType().equals("GUIDE")).findFirst().orElseThrow();
        var b = second.getDocuments().stream().filter(d -> d.getSourceType().equals("GUIDE")).findFirst().orElseThrow();
        assertThat(a.getId()).isEqualTo(b.getId());
        assertThat(a.getContentHash()).isNotEqualTo(b.getContentHash());
    }
}
