package com.rutikrokade.projects.aicartapp.util;

import java.util.UUID;
import java.util.regex.Pattern;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ResourceIdGenerator {

    private static final Pattern SESSION_ID = Pattern.compile(
            "SESSION_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public static String newSessionId() {
        return "SESSION_" + UUID.randomUUID();
    }

    public static String newCartId() {
        return "CART_" + UUID.randomUUID();
    }

    public static boolean isSessionId(String value) {
        return value != null && SESSION_ID.matcher(value).matches();
    }
}
