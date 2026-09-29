package com.dentallab.repo;

import com.dentallab.model.ReworkRecord;
import com.dentallab.model.ReworkStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ReworkRecordRepo extends JpaRepository<ReworkRecord, Long> {
    List<ReworkRecord> findByOrderIdOrderByIdAsc(Long orderId);

    List<ReworkRecord> findByToothItemIdAndStatus(Long toothItemId, ReworkStatus status);

    List<ReworkRecord> findByToothItemIdAndStatusIn(Long toothItemId, Collection<ReworkStatus> statuses);

    List<ReworkRecord> findByOrderIdIn(Collection<Long> orderIds);
}
