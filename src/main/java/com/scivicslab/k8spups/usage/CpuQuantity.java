package com.scivicslab.k8spups.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Converts a Kubernetes CPU quantity such as {@code 250m}, {@code 4} or {@code 1.5} to millicores (thousandths of
 * a core), the unit in which {@code pod_usage} keeps the CPU request and the CPU limit of a Pod.
 */
public final class CpuQuantity {

    private CpuQuantity() {}

    /**
     * @param quantity a CPU quantity, or null
     * @return the quantity in millicores; null when {@code quantity} is null or blank
     * @throws IllegalArgumentException when the text is not a CPU quantity
     */
    public static Integer toMillicores(String quantity) {
        if (quantity == null || quantity.isBlank()) return null;
        String q = quantity.trim();
        try {
            BigDecimal millicores = q.endsWith("m")
                ? new BigDecimal(q.substring(0, q.length() - 1))
                : new BigDecimal(q).multiply(BigDecimal.valueOf(1000));
            if (millicores.signum() < 0) throw new IllegalArgumentException("Negative CPU quantity: " + quantity);
            return millicores.setScale(0, RoundingMode.HALF_UP).intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("Not a CPU quantity: " + quantity, e);
        }
    }
}
