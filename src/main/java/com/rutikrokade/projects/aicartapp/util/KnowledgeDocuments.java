package com.rutikrokade.projects.aicartapp.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.rutikrokade.projects.aicartapp.dto.*;

public final class KnowledgeDocuments {

    public static final String CORPUS = "restaurant-v1";
    private static final Encoding TOKENIZER = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);
    private static final List<String> SPICE = List.of("none", "mild", "medium", "spicy", "very spicy", "extra hot");

    private KnowledgeDocuments() {
    }

    public static KnowledgeDocumentDto menu(SourceMenuItemDto item, String path) {
        String text = """
                Dish: %s
                Category: %s
                Description: %s
                Ingredients: %s
                Dietary tags: %s
                Spice level: %s of 5 — %s
                """.formatted(item.getName().strip(), item.getCategory().strip(), item.getDescription().strip(),
                String.join(", ", sorted(item.getIngredients())), String.join(", ", sorted(item.getDietaryTags())),
                item.getSpiceLevel(), SPICE.get(item.getSpiceLevel()));
        return document("MENU", "menu:" + item.getSourceKey(), path, item.getName().strip(), text, item.getSourceKey());
    }

    public static KnowledgeDocumentDto document(String type, String sourceId, String path, String title,
                                                String text, String key) {
        String content = canonical(text);
        if (TOKENIZER.countTokens(content) > 600) {
            throw new IllegalArgumentException("Document exceeds 600 estimated tokens: " + sourceId
                    + ". Split the guide topic into separately identified sections.");
        }
        return new KnowledgeDocumentDto(UUID.nameUUIDFromBytes(("aicart:" + sourceId).getBytes(StandardCharsets.UTF_8)),
                type, sourceId, path, title, content, hash(content), key);
    }

    public static List<String> sorted(List<String> values) {
        return values.stream().map(String::strip).sorted().toList();
    }

    public static String canonical(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n')
                .lines().map(String::stripTrailing).reduce((a, b) -> a + "\n" + b).orElse("").strip();
    }

    public static String normalizedName(String name) {
        return name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static Map<String, Object> metadata(KnowledgeDocumentDto doc, String fingerprint, Long itemId) {
        Map<String, Object> values = new TreeMap<>();
        values.put("corpusId", CORPUS);
        values.put("sourceType", doc.getSourceType());
        values.put("sourceId", doc.getSourceId());
        values.put("sourcePath", doc.getSourcePath());
        values.put("title", doc.getTitle());
        values.put("contentHash", doc.getContentHash());
        values.put("embeddingFingerprint", fingerprint);
        if (itemId != null) {
            values.put("itemId", itemId);
        }
        return values;
    }
}
