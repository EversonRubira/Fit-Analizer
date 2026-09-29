package com.fitanalizer.profile.dto;

import com.fitanalizer.profile.ExperienciaProfissional;
import com.fitanalizer.profile.Frente;
import java.time.LocalDate;
import java.util.List;

public record ExperienciaResponse(
        Long id,
        String empresa,
        String cargo,
        Frente frente,
        LocalDate dataInicio,
        LocalDate dataFim,
        List<String> tecnologiasUsadas) {

    public static ExperienciaResponse de(ExperienciaProfissional experiencia) {
        return new ExperienciaResponse(
                experiencia.getId(),
                experiencia.getEmpresa(),
                experiencia.getCargo(),
                experiencia.getFrente(),
                experiencia.getDataInicio(),
                experiencia.getDataFim(),
                experiencia.getTecnologiasUsadas());
    }
}
