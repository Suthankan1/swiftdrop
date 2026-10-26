package com.swiftdrop.graphql.batching;

import graphql.GraphQLContext;
import org.dataloader.DataLoader;

/** Bean definitions are singletons; create must return a NEW loader capturing only this request's verified context. */
public interface RequestBatchLoader {
    String name();
    DataLoader<?, ?> create(GraphQLContext context);
}
