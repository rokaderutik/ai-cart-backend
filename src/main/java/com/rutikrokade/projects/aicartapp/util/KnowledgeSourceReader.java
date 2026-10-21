package com.rutikrokade.projects.aicartapp.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import com.rutikrokade.projects.aicartapp.dto.*;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

@RequiredArgsConstructor
public class KnowledgeSourceReader {

    private static final Pattern HEADING = Pattern.compile("^## (.+?) \\{#([a-z0-9]+(?:-[a-z0-9]+)*)}$");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final Validator validator;

    public KnowledgeSourceDto read(Resource menuResource, Resource guideResource) {
        try {
            String menuText = menuResource.getContentAsString(StandardCharsets.UTF_8);
            String guideText = guideResource.getContentAsString(StandardCharsets.UTF_8);
            List<SourceMenuItemDto> menu = JSON.readValue(menuText,
                    JSON.getTypeFactory().constructCollectionType(List.class, SourceMenuItemDto.class));
            if (menu == null || menu.isEmpty()) {
                throw new IllegalArgumentException("Menu must contain at least one dish");
            }
            Set<String> keys = new HashSet<>();
            Set<String> names = new HashSet<>();
            List<KnowledgeDocumentDto> docs = new ArrayList<>();
            for (SourceMenuItemDto item : menu) {
                if (item == null) {
                    throw new IllegalArgumentException("Null menu entry");
                }
                var violations = validator.validate(item);
                if (!violations.isEmpty()) {
                    throw new IllegalArgumentException("Invalid menu entry " + item.getSourceKey() + ": "
                            + violations.stream().map(v -> v.getPropertyPath() + " " + v.getMessage()).sorted().toList());
                }
                if (!keys.add(item.getSourceKey()) || !names.add(KnowledgeDocuments.normalizedName(item.getName()))) {
                    throw new IllegalArgumentException("Duplicate menu key or name: " + item.getSourceKey());
                }
                var tags = item.getDietaryTags();
                if (tags.contains("NON_VEGETARIAN") && (tags.contains("VEGAN") || tags.contains("VEGETARIAN"))) {
                    throw new IllegalArgumentException("Contradictory dietary tags: " + item.getSourceKey());
                }
                if (new HashSet<>(tags).size() != tags.size()
                        || new HashSet<>(KnowledgeDocuments.sorted(item.getIngredients())).size() != item.getIngredients().size()) {
                    throw new IllegalArgumentException("Duplicate tags or ingredients: " + item.getSourceKey());
                }
                docs.add(KnowledgeDocuments.menu(item, "knowledge/menu.json"));
            }
            docs.addAll(guide(guideText));
            menu.sort(Comparator.comparing(SourceMenuItemDto::getSourceKey));
            docs.sort(Comparator.comparing(KnowledgeDocumentDto::getSourceId));
            // A canonical source hash also tracks non-embedded menu fields such as price.
            List<String> rows = menu.stream().map(item -> item.getSourceKey() + "|"
                    + KnowledgeDocuments.menu(item, "knowledge/menu.json").getContentHash() + "|"
                    + item.getPrice().stripTrailingZeros().toPlainString() + "|" + item.getCurrency()
                    + "|" + item.getAvailable()).toList();
            String hash = KnowledgeDocuments.hash(String.join("\n", rows) + "\n"
                    + docs.stream().map(d -> d.getSourceId() + ":" + d.getContentHash()).reduce("", (a, v) -> a + "\n" + v));
            return new KnowledgeSourceDto(List.copyOf(menu), List.copyOf(docs), hash);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read both knowledge source files");
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalArgumentException("Invalid menu JSON; check field names and value types");
        }
    }

    private List<KnowledgeDocumentDto> guide(String text) {
        List<KnowledgeDocumentDto> docs = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        String title = null;
        String id = null;
        StringBuilder body = new StringBuilder();
        for (String line : KnowledgeDocuments.canonical(text).split("\n")) {
            if (line.startsWith("## ")) {
                if (id != null) {
                    addGuide(docs, id, title, body.toString());
                }
                var matcher = HEADING.matcher(line);
                if (!matcher.matches() || !ids.add(matcher.group(2))) {
                    throw new IllegalArgumentException("Guide headings require unique stable {#topic-key} identifiers");
                }
                title = matcher.group(1);
                id = matcher.group(2);
                body.setLength(0);
            } else if (id != null) {
                body.append(line).append('\n');
            } else if (!line.isBlank() && !line.startsWith("# ")) {
                throw new IllegalArgumentException("Put guide content inside an identified ## section");
            }
        }
        if (id != null) {
            addGuide(docs, id, title, body.toString());
        }
        if (docs.isEmpty()) {
            throw new IllegalArgumentException("Guide must contain at least one identified topic");
        }
        return docs;
    }

    private void addGuide(List<KnowledgeDocumentDto> docs, String id, String title, String body) {
        if (body.isBlank()) {
            throw new IllegalArgumentException("Empty guide topic: " + id);
        }
        docs.add(KnowledgeDocuments.document("GUIDE", "guide:" + id, "knowledge/restaurant-guide.md",
                title, "Demo restaurant guide\nTopic: " + title + "\n" + body, null));
    }
}
