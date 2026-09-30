package com.fitanalizer.match.dto;

import com.fitanalizer.match.Classificacao;
import com.fitanalizer.match.RequisitoClassificado;

public record RequisitoResponse(String descricao, Classificacao classificacao, String evidenciaRef) {

    public static RequisitoResponse de(RequisitoClassificado requisito) {
        return new RequisitoResponse(requisito.getDescricao(), requisito.getClassificacao(),
                requisito.getEvidenciaRef());
    }
}
