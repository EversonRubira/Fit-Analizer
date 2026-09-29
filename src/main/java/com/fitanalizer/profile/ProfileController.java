package com.fitanalizer.profile;

import com.fitanalizer.profile.dto.ErrorResponse;
import com.fitanalizer.profile.dto.ExperienciaRequest;
import com.fitanalizer.profile.dto.ExperienciaResponse;
import com.fitanalizer.profile.dto.ExperienciaUpdateRequest;
import com.fitanalizer.profile.dto.ProfileCreateRequest;
import com.fitanalizer.profile.dto.ProfileResponse;
import com.fitanalizer.profile.dto.ProfileUpdateRequest;
import com.fitanalizer.profile.dto.SkillRequest;
import com.fitanalizer.profile.dto.SkillResponse;
import jakarta.validation.Valid;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Try-catch por endpoint, sem @ControllerAdvice global (decisão registrada
 * na Spec F01, seção 1.2 — gatilho de revisão: se o padrão de erro começar a
 * se repetir de forma relevante entre muitos endpoints).
 *
 * Exceção: erro de validação de Bean Validation (@Valid) acontece antes do
 * corpo do método rodar, então try-catch não alcança. Um único
 * @ExceptionHandler no fim desta classe cobre isso — é local a este
 * Controller, não um @ControllerAdvice global, então não contradiz a decisão
 * acima; existe só para manter o corpo de erro (ErrorResponse) consistente
 * também nos 400s.
 */
@RestController
@RequestMapping("/profiles")
public class ProfileController {

    private final ProfileService service;

    public ProfileController(ProfileService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> criar(@Valid @RequestBody ProfileCreateRequest request) {
        try {
            Profile profile = service.criar(request.owner(), request.bio());
            return ResponseEntity.status(HttpStatus.CREATED).body(ProfileResponse.de(profile));
        } catch (ProfileAlreadyExistsException e) {
            return erro(HttpStatus.CONFLICT, e);
        }
    }

    @GetMapping("/{owner}")
    public ResponseEntity<?> buscar(@PathVariable String owner) {
        try {
            Profile profile = service.buscar(owner);
            return ResponseEntity.ok(ProfileResponse.de(profile));
        } catch (ProfileNotFoundException e) {
            return erro(HttpStatus.NOT_FOUND, e);
        }
    }

    @PatchMapping("/{owner}")
    public ResponseEntity<?> atualizar(@PathVariable String owner, @Valid @RequestBody ProfileUpdateRequest request) {
        try {
            Profile profile = service.atualizar(owner, request.bio());
            return ResponseEntity.ok(ProfileResponse.de(profile));
        } catch (ProfileNotFoundException e) {
            return erro(HttpStatus.NOT_FOUND, e);
        }
    }

    @DeleteMapping("/{owner}")
    public ResponseEntity<?> excluir(@PathVariable String owner) {
        try {
            service.excluir(owner);
            return ResponseEntity.noContent().build();
        } catch (ProfileNotFoundException e) {
            return erro(HttpStatus.NOT_FOUND, e);
        }
    }

    @PostMapping("/{owner}/skills")
    public ResponseEntity<?> adicionarSkill(@PathVariable String owner, @Valid @RequestBody SkillRequest request) {
        try {
            Skill skill = service.adicionarSkill(owner, request.nome(), request.anosExperiencia(), request.frente());
            return ResponseEntity.status(HttpStatus.CREATED).body(SkillResponse.de(skill));
        } catch (ProfileNotFoundException e) {
            return erro(HttpStatus.NOT_FOUND, e);
        } catch (SkillAlreadyExistsException e) {
            return erro(HttpStatus.CONFLICT, e);
        }
    }

    @DeleteMapping("/{owner}/skills/{nome}")
    public ResponseEntity<?> removerSkill(@PathVariable String owner, @PathVariable String nome) {
        try {
            service.removerSkill(owner, nome);
            return ResponseEntity.noContent().build();
        } catch (ProfileNotFoundException | SkillNotFoundException e) {
            return erro(HttpStatus.NOT_FOUND, e);
        }
    }

    @PostMapping("/{owner}/experiencias")
    public ResponseEntity<?> adicionarExperiencia(@PathVariable String owner,
            @Valid @RequestBody ExperienciaRequest request) {
        try {
            ExperienciaProfissional experiencia = service.adicionarExperiencia(owner, request.empresa(),
                    request.cargo(), request.frente(), request.dataInicio(), request.dataFim(),
                    request.tecnologiasUsadas());
            return ResponseEntity.status(HttpStatus.CREATED).body(ExperienciaResponse.de(experiencia));
        } catch (ProfileNotFoundException e) {
            return erro(HttpStatus.NOT_FOUND, e);
        }
    }

    @PatchMapping("/{owner}/experiencias/{id}")
    public ResponseEntity<?> atualizarExperiencia(@PathVariable String owner, @PathVariable Long id,
            @Valid @RequestBody ExperienciaUpdateRequest request) {
        try {
            ExperienciaProfissional experiencia = service.atualizarExperiencia(owner, id, request.empresa(),
                    request.cargo(), request.frente(), request.dataInicio(), request.dataFim(),
                    request.tecnologiasUsadas());
            return ResponseEntity.ok(ExperienciaResponse.de(experiencia));
        } catch (ProfileNotFoundException | ExperienciaNotFoundException e) {
            return erro(HttpStatus.NOT_FOUND, e);
        }
    }

    @DeleteMapping("/{owner}/experiencias/{id}")
    public ResponseEntity<?> removerExperiencia(@PathVariable String owner, @PathVariable Long id) {
        try {
            service.removerExperiencia(owner, id);
            return ResponseEntity.noContent().build();
        } catch (ProfileNotFoundException | ExperienciaNotFoundException e) {
            return erro(HttpStatus.NOT_FOUND, e);
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
