package com.swiftdrop.graphql.limits;

import java.util.Map;

public record PaginationPolicy(int page, int size) {
    public static final int MAX_SIZE = 50;
    public PaginationPolicy {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("Invalid pagination");
        }
    }
    public static PaginationPolicy from(Map<String, Object> value) {
        if (value == null) return new PaginationPolicy(0, 20);
        return new PaginationPolicy(value.get("page") == null ? 0 : (Integer) value.get("page"),
                value.get("size") == null ? 20 : (Integer) value.get("size"));
    }
}
