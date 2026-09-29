package com.dentallab.service;

import com.dentallab.config.BizException;
import com.dentallab.model.*;
import com.dentallab.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 试戴：诊所回填试戴结果（咬合高/边缘不密合）、医生备注与试戴照片。
 * 不密合 → 自动登记异常并创建“待诊所确认”返工单，关联试戴那版设计、医生备注、技工操作、试戴照片；
 * 诊所确认返工时限与是否收费后，牙位才打回生产线。
 */
@Service
public class TryInService {

    private final TryInRecordRepo tryInRepo;
    private final DentureOrderRepo orderRepo;
    private final ToothItemRepo itemRepo;
    private final OrderExceptionRepo exceptionRepo;
    private final ReworkService reworkService;
    private final AuthService authService;

    public TryInService(TryInRecordRepo tryInRepo, DentureOrderRepo orderRepo, ToothItemRepo itemRepo,
                        OrderExceptionRepo exceptionRepo, ReworkService reworkService,
                        AuthService authService) {
        this.tryInRepo = tryInRepo;
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.exceptionRepo = exceptionRepo;
        this.reworkService = reworkService;
        this.authService = authService;
    }

    /**
     * 记录试戴结果。toothItemId 为空表示整单试戴。
     * 不密合必须指明 misfitType（HIGH_BITE 咬合高 / EDGE_MISFIT 边缘不密合），
     * 可附医生备注 doctorNotes 与试戴照片 photos。
     */
    @Transactional
    public Map<String, Object> recordResult(Account doctor, Long orderId, Long toothItemId,
                                            TryInResult result, MisfitType misfitType,
                                            String doctorNotes, String feedback, Integer experienceScore,
                                            List<String> photos) {
        authService.requireRole(doctor, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN);
        DentureOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> BizException.notFound("订单#" + orderId));
        if (doctor.getClinic() == null || !doctor.getClinic().getId().equals(order.getClinic().getId())) {
            throw BizException.forbidden("只能记录本诊所订单的试戴结果");
        }
        if (experienceScore != null && (experienceScore < 1 || experienceScore > 5)) {
            throw new BizException("患者体验评分 experienceScore 必须在 1-5 之间");
        }
        if (result == TryInResult.MISFIT && misfitType == null) {
            throw new BizException("试戴不密合必须指明问题类型 misfitType：HIGH_BITE(咬合高) / EDGE_MISFIT(边缘不密合)");
        }
        ToothItem item = null;
        if (toothItemId != null) {
            item = itemRepo.findById(toothItemId)
                    .orElseThrow(() -> BizException.notFound("牙位明细#" + toothItemId));
            if (!item.getOrder().getId().equals(orderId)) {
                throw new BizException("牙位明细#" + toothItemId + "不属于订单#" + orderId);
            }
        }

        TryInRecord record = new TryInRecord();
        record.setOrder(order);
        record.setToothItem(item);
        record.setScheduledAt(LocalDateTime.now());
        record.setActualAt(LocalDateTime.now());
        record.setResult(result);
        record.setMisfitType(result == TryInResult.MISFIT ? misfitType : null);
        record.setDoctorNotes(doctorNotes);
        record.setFeedback(feedback);
        record.setExperienceScore(experienceScore);
        if (photos != null && !photos.isEmpty()) {
            record.setPhotos(String.join(",", photos));
        }
        record.setRecordedBy(doctor);
        tryInRepo.save(record);

        List<ToothItem> targets = item != null ? List.of(item) : itemRepo.findByOrderIdOrderByIdAsc(orderId);
        if (result == TryInResult.FIT) {
            for (ToothItem t : targets) {
                t.setStatus(ToothItemStatus.TRY_IN_OK);
                t.setUpdatedAt(LocalDateTime.now());
                itemRepo.save(t);
            }
        } else {
            for (ToothItem t : targets) {
                t.setStatus(ToothItemStatus.AWAIT_REWORK_CONFIRM);
                t.setUpdatedAt(LocalDateTime.now());
                itemRepo.save(t);
                // 创建待诊所确认返工单，固定证据链：设计版本/医生备注/技工操作/试戴照片
                reworkService.openRework(order, t, ReworkSource.TRY_IN_MISFIT,
                        doctorNotes, misfitType, record);
            }
            OrderException event = new OrderException();
            event.setOrder(order);
            event.setToothItem(item);
            event.setType(ExceptionType.TRY_IN_MISFIT);
            event.setDescription("试戴不密合（" + misfitType.getLabel() + "）"
                    + (item != null ? "（牙位 " + item.getToothPosition() + "）" : "")
                    + ": " + (doctorNotes == null ? "" : doctorNotes));
            event.setReportedBy(doctor);
            exceptionRepo.save(event);
        }

        refreshOrderAfterTryIn(order);
        return Views.tryIn(record);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listByOrder(Long orderId) {
        return tryInRepo.findByOrderIdOrderByIdAsc(orderId).stream()
                .map(Views::tryIn).collect(Collectors.toList());
    }

    private void refreshOrderAfterTryIn(DentureOrder order) {
        List<ToothItem> items = itemRepo.findByOrderIdOrderByIdAsc(order.getId());
        boolean allOk = items.stream().allMatch(i -> i.getStatus() == ToothItemStatus.TRY_IN_OK);
        if (allOk && !items.isEmpty()) {
            order.setStatus(OrderStatus.COMPLETED);
            order.setCompletedAt(LocalDateTime.now());
        }
        // 存在不密合牙位时订单仍处于试戴阶段，诊所确认返工后由返工流程打回生产
        order.setUpdatedAt(LocalDateTime.now());
        orderRepo.save(order);
    }
}
