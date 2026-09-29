package com.fitanalizer.profile;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "profiles", uniqueConstraints = @UniqueConstraint(name = "uk_profiles_owner", columnNames = "owner"))
public class Profile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner", nullable = false)
    private String owner;

    @Column(name = "bio", length = 2000)
    private String bio;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "profile_skills", joinColumns = @JoinColumn(name = "profile_id"))
    private List<Skill> skills = new ArrayList<>();

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ExperienciaProfissional> historicoProfissional = new ArrayList<>();

    protected Profile() {
    }

    public Profile(String owner) {
        this.owner = owner;
    }

    public Long getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public List<Skill> getSkills() {
        return skills;
    }

    public void setSkills(List<Skill> skills) {
        this.skills.clear();
        if (skills != null) {
            this.skills.addAll(skills);
        }
    }

    public void addSkill(Skill skill) {
        this.skills.add(skill);
    }

    public void removeSkill(Skill skill) {
        this.skills.remove(skill);
    }

    public List<ExperienciaProfissional> getHistoricoProfissional() {
        return historicoProfissional;
    }

    public void setHistoricoProfissional(List<ExperienciaProfissional> historicoProfissional) {
        this.historicoProfissional.forEach(experiencia -> experiencia.setProfile(null));
        this.historicoProfissional.clear();
        if (historicoProfissional != null) {
            historicoProfissional.forEach(this::addExperienciaProfissional);
        }
    }

    public void addExperienciaProfissional(ExperienciaProfissional experiencia) {
        experiencia.setProfile(this);
        this.historicoProfissional.add(experiencia);
    }

    public void removeExperienciaProfissional(ExperienciaProfissional experiencia) {
        this.historicoProfissional.remove(experiencia);
        experiencia.setProfile(null);
    }
}
