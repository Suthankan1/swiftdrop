package com.swiftdrop.accountsservice.admin;

import java.util.List;

public record AdminPageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public AdminPageResponse {
        content = List.copyOf(content);
    }
}
