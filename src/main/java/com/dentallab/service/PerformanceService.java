package com.dentallab.service;

import com.dentallab.model.*;
import com.dentallab.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/** 技工绩效：完成工序数、准时率、经手牙位的返工情况、材料用量。 */
@Service
public class PerformanceService {

    private final AccountRepo accountRepo;
    private final StageRecordRepo stageRepo;
    private final ReworkRecordRepo reworkRepo;

    public PerformanceService(AccountRepo accountRepo, StageRecordRepo stageRepo, ReworkRecordRepo reworkRepo) {
        this.accountRepo = accountRepo;
        this.stageRepo = stageRepo;
        this.reworkRepo = reworkRepo;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> technicians() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Account tech : accountRepo.findByRole(Role.TECHNICIAN)) {
            List<StageRecord> stages = stageRepo.findByOperatorId(tech.getId());
            long completed = stages.stream().filter(s -> s.getFinishedAt() != null).count();
            long onTime = stages.stream()
                    .filter(s -> s.getFinishedAt() != null && s.getEstimatedFinishAt() != null
                            && !s.getFinishedAt().isAfter(s.getEstimatedFinishAt()))
                    .count();
            long withEstimate = stages.stream()
                    .filter(s -> s.getFinishedAt() != null && s.getEstimatedFinishAt() != null).count();
            BigDecimal materialUsed = stages.stream()
                    .map(s -> s.getMaterialQty() == null ? BigDecimal.ZERO : s.getMaterialQty())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 经手牙位的返工：该技工做过工序的牙位上发生的返工（按牙位去重）
            Set<Long> itemIds = stages.stream().map(s -> s.getToothItem().getId()).collect(Collectors.toSet());
            long reworkItems = itemIds.stream()
                    .filter(id -> !reworkRepo.findByToothItemIdAndStatus(id, ReworkStatus.CONFIRMED).isEmpty()
                            || reworkTouched(id))
                    .count();

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("technicianId", tech.getId());
            m.put("technicianName", tech.getDisplayName());
            m.put("stagesCompleted", completed);
            m.put("onTimeRate", withEstimate == 0 ? null
                    : String.format("%.2f", (double) onTime / withEstimate));
            m.put("materialQtyUsed", materialUsed);
            m.put("reworkedItemCount", reworkItems);
            result.add(m);
        }
        return result;
    }

    private boolean reworkTouched(Long itemId) {
        // 该牙位历史上是否存在返工记录（含已完成）
        return !reworkRepo.findByToothItemIdAndStatus(itemId, ReworkStatus.COMPLETED).isEmpty();
    }
}
