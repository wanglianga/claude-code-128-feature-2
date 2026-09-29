package com.dentallab.repo;

import com.dentallab.model.ReworkCause;
import com.dentallab.model.ReworkRecord;
import com.dentallab.model.ReworkStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ReworkRecordRepo extends JpaRepository<ReworkRecord, Long> {
    List<ReworkRecord> findByOrderIdOrderByIdAsc(Long orderId);

    List<ReworkRecord> findByToothItemIdAndStatus(Long toothItemId, ReworkStatus status);

    /** 牙位上未完成（非 COMPLETED）的返工单，用于防止重复发起与重做自动关闭。 */
    List<ReworkRecord> findByToothItemIdAndStatusNot(Long toothItemId, ReworkStatus status);

    List<ReworkRecord> findByOrderIdIn(Collection<Long> orderIds);

    long countByCause(ReworkCause cause);
}
