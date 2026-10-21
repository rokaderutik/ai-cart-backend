package com.rutikrokade.projects.aicartapp.exception;

import java.util.Locale;

import com.google.genai.errors.ApiException;
import lombok.Getter;

/** Safe provider diagnostics: never carries the raw response, API key or request text. */
@Getter
public class EmbeddingProviderException extends IllegalStateException {

    private final int statusCode;

    private final String reason;

    public EmbeddingProviderException(ApiException failure) {
        this(failure.code(), reason(failure));
    }

    private EmbeddingProviderException(int statusCode, String reason) {
        super("Gemini embedding HTTP " + statusCode + ": " + reason);
        this.statusCode = statusCode;
        this.reason = reason;
    }

    private static String reason(ApiException failure) {
        String message = failure.message() == null ? "" : failure.message().toLowerCase(Locale.ROOT);
        if (message.contains("leaked")) {
            return "API key was reported as leaked; replace it in the active run environment";
        }
        if (message.contains("api key expired") || message.contains("api key not valid")
                || message.contains("invalid api key") || message.contains("api_key_invalid")) {
            return "API key is invalid or expired; check GOOGLE_API_KEY and IntelliJ environment overrides";
        }
        if (message.contains("location is not supported")) {
            return "Gemini API is unavailable from this location";
        }
        if (message.contains("blocked") || message.contains("referer") || message.contains("ip address")) {
            return "API key or request is blocked by a provider restriction";
        }
        if (message.contains("quota") || message.contains("rate limit") || failure.code() == 429) {
            return "provider quota or rate limit exceeded; check Google AI Studio usage";
        }
        return switch (failure.code()) {
            case 400 -> "provider rejected the request; check model, task type and embedding options";
            case 401 -> "authentication failed; check GOOGLE_API_KEY in the active run environment";
            case 403 -> "permission denied; check API key restrictions and project access";
            case 404 -> "model or endpoint unavailable; check the configured embedding model";
            default -> failure.code() >= 500
                    ? "provider temporarily unavailable; retry ingestion"
                    : "provider rejected the request; check the active Google project configuration";
        };
    }
}
