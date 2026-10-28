package com.swiftdrop.graphql.resolver;

import graphql.schema.DataFetchingEnvironment;

/** Extension point: one owner-supplied bean per approved root. Never register a default fetcher. */
public interface RootQueryResolver {
    String field();
    Object resolve(DataFetchingEnvironment environment) throws Exception;
}
