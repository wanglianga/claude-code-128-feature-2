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
 * 试戴：诊所医生回填试戴结果（咬合高/边缘不密合）、医生备注与试戴照片。
 * 不密合自动触发“试戴不密合”异常，并对牙位创建返工单（待技工所提交返工方案），
 * 返工单关联设计版本、医生备注、技工可查看的试戴照片。
 */
@Service
public class TryInService {

    private final TryInRecordRepo tryInRepo;
    private final DentureOrderRepo orderRepo;
    private final ToothItemRepo itemRepo;
    private final OrderExceptionRepo exceptionRepo;
    private final ReworkRecordRepo reworkRepo;
    private final DesignVersionRepo designVersionRepo;
    private final AuthService authService;

    public TryInService(TryInRecordRepo tryInRepo, DentureOrderRepo orderRepo, ToothItemRepo itemRepo,
                        OrderExceptionRepo exceptionRepo, ReworkRecordRepo reworkRepo,
                        DesignVersionRepo designVersionRepo, AuthService authService) {
        this.tryInRepo = tryInRepo;
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.exceptionRepo = exceptionRepo;
        this.reworkRepo = reworkRepo;
        this.designVersionRepo = designVersionRepo;
        this.authService = authService;
    }

    /**
     * 记录试戴结果。toothItemId 为空表示整单试戴。
     * result=FIT 密合；MISFIT 需指定 issueType（EDGE_GAP 边缘不密合）；HIGH_BITE 咬合高。
     * 不密合/咬合高：牙位打回，创建待方案返工单（关联当前设计版本、医生备注、试戴照片），并自动登记异常。
     */
    @Transactional
    public Map<String, Object> recordResult(Account doctor, Long orderId, Long toothItemId,
                                            TryInResult result, MisfitType issueType, String feedback,
                                            List<String> photos, Integer experienceScore) {
        authService.requireRole(doctor, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN);
        DentureOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> BizException.notFound("订单#" + orderId));
        if (doctor.getClinic() == null || !doctor.getClinic().getId().equals(order.getClinic().getId())) {
            throw BizException.forbidden("只能记录本诊所订单的试戴结果");
        }
        if (experienceScore != null && (experienceScore < 1 || experienceScore > 5)) {
            throw new BizException("患者体验评分 experienceScore 必须在 1-5 之间");
        }
        MisfitType effectiveIssue = issueType;
        if (result == TryInResult.HIGH_BITE && effectiveIssue == null) {
            effectiveIssue = MisfitType.HIGH_BITE;
        }
        if (result != TryInResult.FIT && effectiveIssue == null) {
            throw new BizException("不密合试戴必须说明问题类型 issueType：HIGH_BITE(咬合高)/EDGE_GAP(边缘不密合)/BOTH(两者)");
        }
        if (result == TryInResult.FIT) {
            effectiveIssue = null;
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
        record.setIssueType(effectiveIssue);
        record.setFeedback(feedback);
        if (photos != null && !photos.isEmpty()) {
            record.setPhotos(String.join(",", photos));
        }
        record.setExperienceScore(experienceScore);
        record.setDoctor(doctor);

        List<ToothItem> targets = item != null ? List.of(item) : itemRepo.findByOrderIdOrderByIdAsc(orderId);
        if (result == TryInResult.FIT) {
            for (ToothItem t : targets) {
                t.setStatus(ToothItemStatus.TRY_IN_OK);
                t.setUpdatedAt(LocalDateTime.now());
                itemRepo.save(t);
            }
        } else {
            String photoStr = record.getPhotos();
            for (ToothItem t : targets) {
                t.setStatus(ToothItemStatus.REWORKING);
                t.setCurrentStage(null);
                t.setUpdatedAt(LocalDateTime.now());
                itemRepo.save(t);
                // 同一牙位已有未完成返工单时不重复创建
                if (reworkRepo.findByToothItemIdAndStatusNot(t.getId(), ReworkStatus.COMPLETED).isEmpty()) {
                    ReworkRecord rework = new ReworkRecord();
                    rework.setOrder(order);
                    rework.setToothItem(t);
                    rework.setIssueType(effectiveIssue);
                    rework.setTryInPhotos(photoStr);
                    rework.setDoctorNotes(feedback);
                    rework.setReason("试戴" + effectiveIssue.getLabel() + ": "
                            + (feedback == null || feedback.isBlank() ? "医生试戴反馈" : feedback));
                    rework.setStatus(ReworkStatus.AWAITING_PLAN);
                    // 返工关联设计版本：当前牙位最近一版设计
                    designVersionRepo.findTopByToothItemIdOrderByVersionNoDesc(t.getId())
                            .ifPresent(rework::setDesignVersion);
                    reworkRepo.save(rework);
                    record.setRework(rework);
                }
            }
            OrderException event = new OrderException();
            event.setOrder(order);
            event.setToothItem(item);
            event.setType(ExceptionType.TRY_IN_MISFIT);
            event.setDescription("试戴" + effectiveIssue.getLabel()
                    + (item != null ? "（牙位 " + item.getToothPosition() + "）" : "")
                    + ": " + (feedback == null ? "" : feedback)
                    + (photos != null && !photos.isEmpty() ? "；附试戴照片 " + photos.size() + " 张" : ""));
            event.setReportedBy(doctor);
            exceptionRepo.save(event);
        }
        tryInRepo.save(record);

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
        boolean anyReworking = items.stream().anyMatch(i -> i.getStatus() == ToothItemStatus.REWORKING);
        if (allOk && !items.isEmpty()) {
            order.setStatus(OrderStatus.COMPLETED);
            order.setCompletedAt(LocalDateTime.now());
        } else if (anyReworking) {
            order.setStatus(OrderStatus.IN_PRODUCTION);
        }
        order.setUpdatedAt(LocalDateTime.now());
        orderRepo.save(order);
    }
}
