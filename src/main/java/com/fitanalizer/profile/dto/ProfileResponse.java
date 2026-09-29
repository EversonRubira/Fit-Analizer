package com.fitanalizer.profile.dto;

import com.fitanalizer.profile.Profile;
import java.util.List;

public record ProfileResponse(
        Long id,
        String owner,
        String bio,
        List<SkillResponse> skills,
        List<ExperienciaResponse> historicoProfissional) {

    public static ProfileResponse de(Profile profile) {
        return new ProfileResponse(
                profile.getId(),
                profile.getOwner(),
                profile.getBio(),
                profile.getSkills().stream().map(SkillResponse::de).toList(),
                profile.getHistoricoProfissional().stream().map(ExperienciaResponse::de).toList());
    }
}
