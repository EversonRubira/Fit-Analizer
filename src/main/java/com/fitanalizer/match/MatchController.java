package com.fitanalizer.match;

import com.fitanalizer.match.dto.MatchRequest;
import com.fitanalizer.match.dto.MatchResponse;
import com.fitanalizer.profile.ProfileNotFoundException;
import com.fitanalizer.profile.dto.ErrorResponse;
import jakarta.validation.Valid;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint único (PRD F02, seção 6.1): mesmo contrato pro uso manual e pro
 * coletor externo. 201 quando uma análise nova foi produzida (ou
 * reanalisada), 200 quando é o resultado já existente devolvido pelo dedup
 * (Spec F02, seção 5) — diferença pensada especificamente pra fase de
 * teste manual com crédito real.
 *
 * Mesmo padrão de try-catch por endpoint da F01 (Spec F01, seção 1.2).
 */
@RestController
@RequestMapping("/matches")
public class MatchController {

    private final MatchService matchService;

    public MatchController(MatchService matchService) {
        this.matchService = matchService;
    }

    @PostMapping
    public ResponseEntity<?> analisar(@Valid @RequestBody MatchRequest request) {
        try {
            AnaliseResultado resultado = matchService.analisar(request.owner(), request.textoVaga(),
                    request.frente(), request.vagaUrl(), Boolean.TRUE.equals(request.reanalisar()));
            HttpStatus status = resultado.analiseNova() ? HttpStatus.CREATED : HttpStatus.OK;
            return ResponseEntity.status(status).body(MatchResponse.de(resultado.matchResult()));
        } catch (FrenteInvalidaException e) {
            return erro(HttpStatus.BAD_REQUEST, e);
        } catch (ProfileNotFoundException e) {
            return erro(HttpStatus.NOT_FOUND, e);
        } catch (FitAnalysisException e) {
            // Erro de dependência externa, não do próprio serviço (Spec F02, seção 3.3).
            return erro(HttpStatus.BAD_GATEWAY, e);
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> erroDeValidacao(MethodArgumentNotValidException e) {
        String mensagem = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ErrorResponse(mensagem));
    }

    private ResponseEntity<ErrorResponse> erro(HttpStatus status, RuntimeException e) {
        return ResponseEntity.status(status).body(new ErrorResponse(e.getMessage()));
    }
}
