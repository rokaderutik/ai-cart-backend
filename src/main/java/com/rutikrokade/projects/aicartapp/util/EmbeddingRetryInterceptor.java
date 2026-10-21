package com.rutikrokade.projects.aicartapp.util;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import okhttp3.Interceptor;
import okhttp3.Response;

/** Bounded HTTP retries; SDK retries are disabled to avoid multiplying attempts. */
public class EmbeddingRetryInterceptor implements Interceptor {

    @Override
    public Response intercept(Chain chain) throws IOException {
        for (int attempt = 1; ; attempt++) {
            long waitMillis = 1000L << (attempt - 1);
            try {
                Response response = chain.proceed(chain.request());
                int status = response.code();
                if (attempt == 3 || !(status == 429 || status >= 500 && status <= 599)) {
                    return response;
                }
                String retryAfter = response.header("Retry-After");
                if (retryAfter != null) {
                    waitMillis = Math.max(waitMillis, retryAfterMillis(retryAfter));
                }
                response.close();
            } catch (IOException failure) {
                if (attempt == 3 || chain.call().isCanceled()) {
                    throw failure;
                }
            }
            try {
                Thread.sleep(Math.min(30_000, waitMillis));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Embedding retry interrupted");
            }
        }
    }

    private long retryAfterMillis(String value) {
        try {
            return Math.min(30, Math.max(0, Long.parseLong(value))) * 1000L;
        } catch (NumberFormatException ignored) {
            try {
                return Math.min(30_000, Math.max(0, Duration.between(Instant.now(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis()));
            } catch (RuntimeException invalidHeader) {
                return 0;
            }
        }
    }
}
