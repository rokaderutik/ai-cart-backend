package com.rutikrokade.projects.aicartapp.util;

public final class VectorValidation {

    private VectorValidation() {
    }

    public static void requireValid(float[] vector, int dimensions) {
        if (vector == null || vector.length != dimensions) {
            throw new IllegalArgumentException("Embedding must contain exactly " + dimensions + " values");
        }
        double sum = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Embedding contains non-finite values");
            }
            sum += (double) value * value;
        }
        if (sum == 0) {
            throw new IllegalArgumentException("Embedding has zero magnitude");
        }
    }

    public static float[] normalize(float[] vector, int dimensions) {
        requireValid(vector, dimensions);
        double sum = 0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        double norm = Math.sqrt(sum);
        float[] normalized = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            normalized[i] = (float) (vector[i] / norm);
        }
        return normalized;
    }
}
