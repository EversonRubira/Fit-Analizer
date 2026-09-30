package com.fitanalizer.match;

import com.fitanalizer.profile.Profile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchResultRepository extends JpaRepository<MatchResult, Long> {

    Optional<MatchResult> findByProfileAndVagaChave(Profile profile, String vagaChave);
}
