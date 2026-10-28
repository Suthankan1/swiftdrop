package com.swiftdrop.graphql.resolver;

import java.util.Objects;

/**
 * GraphQL pagination descriptor for Query.dispatcherDashboard unassigned parcel queue.
 * Conforms to SDL type PageInfo.
 */
public record PageInfo(
        int currentPage,
        int pageSize,
        int totalElements,
        int totalPages,
        boolean hasNext
) {
    public PageInfo {
        if (currentPage < 0) {
            throw new IllegalArgumentException("currentPage must be >= 0: " + currentPage);
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1: " + pageSize);
        }
        if (totalElements < 0) {
            throw new IllegalArgumentException("totalElements must be >= 0: " + totalElements);
        }
        if (totalPages < 0) {
            throw new IllegalArgumentException("totalPages must be >= 0: " + totalPages);
        }
    }
}
