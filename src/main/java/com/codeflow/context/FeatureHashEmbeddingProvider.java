package com.codeflow.context;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offline multilingual feature-hashing vectors.
 *
 * <p>This fallback is deterministic and dependency-free. It captures lexical,
 * subword and CJK character similarity; configure an OpenAI-compatible embedding
 * endpoint when semantic synonym/cross-language recall is required.</p>
 */
public final class FeatureHashEmbeddingProvider implements EmbeddingProvider {
    private final int dimensions;

    public FeatureHashEmbeddingProvider() {
        this(384);
    }

    public FeatureHashEmbeddingProvider(int dimensions) {
        this.dimensions = Math.max(64, dimensions);
    }

    @Override
    public List<double[]> embed(List<String> texts) {
        var result = new ArrayList<double[]>(texts.size());
        for (String text : texts) result.add(vector(text));
        return result;
    }

    private double[] vector(String input) {
        String value = input == null ? "" : input.toLowerCase(Locale.ROOT);
        double[] out = new double[dimensions];
        for (String token : value.split("[^\\p{L}\\p{N}_-]+")) {
            if (!token.isBlank()) add(out, "w:" + token, 1.0);
            for (int n = 2; n <= 3; n++) {
                for (int i = 0; i + n <= token.length(); i++) {
                    add(out, "n" + n + ":" + token.substring(i, i + n), 0.45);
                }
            }
        }
        double norm = 0;
        for (double item : out) norm += item * item;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < out.length; i++) out[i] /= norm;
        }
        return out;
    }

    private void add(double[] vector, String feature, double weight) {
        byte[] bytes = feature.getBytes(StandardCharsets.UTF_8);
        int hash = 0x811c9dc5;
        for (byte value : bytes) {
            hash ^= value & 0xff;
            hash *= 0x01000193;
        }
        int index = Math.floorMod(hash, dimensions);
        vector[index] += (hash & 0x80000000) == 0 ? weight : -weight;
    }
}
