package com.fitanalizer.profile.dto;

/** Corpo de erro padrão de toda a API. Mínimo de propósito — sem código de
 * erro nem timestamp, porque nenhum consumidor precisa disso hoje. */
public record ErrorResponse(String message) {
}
