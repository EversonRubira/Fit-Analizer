package com.fitanalizer.profile;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "professional_experiences")
public class ExperienciaProfissional {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id", nullable = false)
    private Profile profile;

    @Column(name = "empresa", nullable = false)
    private String empresa;

    @Column(name = "cargo", nullable = false)
    private String cargo;

    @Enumerated(EnumType.STRING)
    @Column(name = "frente", nullable = false)
    private Frente frente;

    @Column(name = "data_inicio", nullable = false)
    private LocalDate dataInicio;

    // null = emprego atual (ainda em andamento)
    @Column(name = "data_fim")
    private LocalDate dataFim;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "experience_technologies", joinColumns = @JoinColumn(name = "experiencia_id"))
    @Column(name = "tecnologia", nullable = false)
    private List<String> tecnologiasUsadas = new ArrayList<>();

    protected ExperienciaProfissional() {
    }

    public ExperienciaProfissional(String empresa, String cargo, Frente frente, LocalDate dataInicio,
            LocalDate dataFim) {
        this.empresa = empresa;
        this.cargo = cargo;
        this.frente = frente;
        this.dataInicio = dataInicio;
        this.dataFim = dataFim;
    }

    public Long getId() {
        return id;
    }

    public Profile getProfile() {
        return profile;
    }

    void setProfile(Profile profile) {
        this.profile = profile;
    }

    public String getEmpresa() {
        return empresa;
    }

    public void setEmpresa(String empresa) {
        this.empresa = empresa;
    }

    public String getCargo() {
        return cargo;
    }

    public void setCargo(String cargo) {
        this.cargo = cargo;
    }

    public Frente getFrente() {
        return frente;
    }

    public void setFrente(Frente frente) {
        this.frente = frente;
    }

    public LocalDate getDataInicio() {
        return dataInicio;
    }

    public void setDataInicio(LocalDate dataInicio) {
        this.dataInicio = dataInicio;
    }

    public LocalDate getDataFim() {
        return dataFim;
    }

    public void setDataFim(LocalDate dataFim) {
        this.dataFim = dataFim;
    }

    public List<String> getTecnologiasUsadas() {
        return tecnologiasUsadas;
    }

    public void setTecnologiasUsadas(List<String> tecnologiasUsadas) {
        this.tecnologiasUsadas.clear();
        if (tecnologiasUsadas != null) {
            this.tecnologiasUsadas.addAll(tecnologiasUsadas);
        }
    }
}
