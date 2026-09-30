package com.fitanalizer.match.dto;

import com.fitanalizer.match.GapRisco;

public record GapRiscoResponse(String gap, String risco) {

    public static GapRiscoResponse de(GapRisco gapRisco) {
        return new GapRiscoResponse(gapRisco.getGap(), gapRisco.getRisco());
    }
}
