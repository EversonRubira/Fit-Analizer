package com.fitanalizer.match;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

@Embeddable
public class GapRisco {

    @Column(name = "gap", nullable = false)
    private String gap;

    @Column(name = "risco", nullable = false)
    private String risco;

    protected GapRisco() {
    }

    public GapRisco(String gap, String risco) {
        this.gap = gap;
        this.risco = risco;
    }

    public String getGap() {
        return gap;
    }

    public String getRisco() {
        return risco;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GapRisco gapRisco)) {
            return false;
        }
        return Objects.equals(gap, gapRisco.gap) && Objects.equals(risco, gapRisco.risco);
    }

    @Override
    public int hashCode() {
        return Objects.hash(gap, risco);
    }
}
