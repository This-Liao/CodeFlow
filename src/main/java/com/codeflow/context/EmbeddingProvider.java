package com.codeflow.context;

import java.util.List;

/** Batch text embedding abstraction used by the Hybrid Context Policy. */
@FunctionalInterface
public interface EmbeddingProvider {
    List<double[]> embed(List<String> texts) throws Exception;
}
