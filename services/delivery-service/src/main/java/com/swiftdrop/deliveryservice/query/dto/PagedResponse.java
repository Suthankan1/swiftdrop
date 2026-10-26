package com.swiftdrop.deliveryservice.query.dto;

import org.springframework.data.domain.Page;

import java.util.Collections;
import java.util.List;

/**
 * Standard paginated response envelope conforming to SwiftDrop API and consumer contracts.
 */
public record PagedResponse<T>(
        List<T> items,
        int currentPage,
        int pageSize,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious
) {
    public PagedResponse {
        items = items != null ? Collections.unmodifiableList(items) : Collections.emptyList();
    }

    public static <T> PagedResponse<T> of(Page<T> page) {
        if (page == null) {
            return new PagedResponse<>(Collections.emptyList(), 0, 0, 0, 0, false, false);
        }
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext(),
                page.hasPrevious()
        );
    }
}
