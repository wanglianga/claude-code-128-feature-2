package com.dentallab.repo;

import com.dentallab.model.DesignVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DesignVersionRepo extends JpaRepository<DesignVersion, Long> {
    List<DesignVersion> findByToothItemIdOrderByVersionAsc(Long toothItemId);

    Optional<DesignVersion> findFirstByToothItemIdOrderByVersionDesc(Long toothItemId);
}
