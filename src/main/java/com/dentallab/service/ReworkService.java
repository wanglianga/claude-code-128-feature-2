package com.dentallab.service;

import com.dentallab.config.BizException;
import com.dentallab.model.*;
import com.dentallab.repo.DentureOrderRepo;
import com.dentallab.repo.DesignVersionRepo;
import com.dentallab.repo.ReworkRecordRepo;
import com.dentallab.repo.StageRecordRepo;
import com.dentallab.repo.ToothItemRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 返工：试戴不密合/配送破损自动建单并关联证据链（设计版本、医生备注、技工操作、试戴照片）；
 * 诊所确认返工时限与是否收费后牙位才打回生产线；技工所随后标记设计/加工/取模原因，
 * 再判定责任与成本分摊，诊所对账前逐条复核。
 */
@Service
public class ReworkService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ReworkRecordRepo reworkRepo;
    private final DentureOrderRepo orderRepo;
    private final ToothItemRepo itemRepo;
    private final StageRecordRepo stageRepo;
    private final DesignVersionRepo designVersionRepo;
    private final AuthService authService;

    public ReworkService(ReworkRecordRepo reworkRepo, DentureOrderRepo orderRepo,
                         ToothItemRepo itemRepo, StageRecordRepo stageRepo,
                         DesignVersionRepo designVersionRepo, AuthService authService) {
        this.reworkRepo = reworkRepo;
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.stageRepo = stageRepo;
        this.designVersionRepo = designVersionRepo;
        this.authService = authService;
    }

    /** 牙位是否存在待诊所确认 / 已确认未结案的返工单。 */
    @Transactional(readOnly = true)
    public boolean hasActiveRework(Long toothItemId) {
        return !reworkRepo.findByToothItemIdAndStatusIn(toothItemId,
                EnumSet.of(ReworkStatus.AWAIT_CONFIRM, ReworkStatus.CONFIRMED)).isEmpty();
    }

    /**
     * 打开一张返工单并固定证据链：试戴那版设计、技工工序操作快照；
     * 试戴类返工额外关联试戴记录（含医生备注与照片）。若已有活动返工单则直接复用。
     */
    @Transactional
    public ReworkRecord openRework(DentureOrder order, ToothItem item, ReworkSource source,
                                   String reason, MisfitType misfitType, TryInRecord tryIn) {
        List<ReworkRecord> active = reworkRepo.findByToothItemIdAndStatusIn(item.getId(),
                EnumSet.of(ReworkStatus.AWAIT_CONFIRM, ReworkStatus.CONFIRMED));
        if (!active.isEmpty()) {
            return active.get(0);
        }
        ReworkRecord rework = new ReworkRecord();
        rework.setOrder(order);
        rework.setToothItem(item);
        rework.setSource(source);
        rework.setMisfitType(misfitType);
        rework.setTryIn(tryIn);
        rework.setReason(buildReason(source, misfitType, reason));
        designVersionRepo.findFirstByToothItemIdOrderByVersionDesc(item.getId())
                .ifPresent(rework::setDesignVersion);
        rework.setTechnicianOps(snapshotTechnicianOps(item));
        return reworkRepo.save(rework);
    }

    /** 人工创建返工单（同样需诊所确认后才打回生产线）。 */
    @Transactional
    public Map<String, Object> create(Account user, Long orderId, Long toothItemId, String reason) {
        authService.requireRole(user, Role.QC_INSPECTOR, Role.TECHNICIAN, Role.CUSTOMER_SERVICE,
                Role.LAB_ADMIN, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN);
        DentureOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> BizException.notFound("订单#" + orderId));
        ToothItem item = itemRepo.findById(toothItemId)
                .orElseThrow(() -> BizException.notFound("牙位明细#" + toothItemId));
        if (!item.getOrder().getId().equals(orderId)) {
            throw new BizException("牙位明细#" + toothItemId + "不属于订单#" + orderId);
        }
        ReworkRecord rework = openRework(order, item, ReworkSource.MANUAL, reason, null, null);
        return Views.rework(rework);
    }

    /** 诊所确认返工：确认返工时限与是否收费，牙位随即打回生产线重做。 */
    @Transactional
    public Map<String, Object> clinicConfirm(Account user, Long reworkId, Integer deadlineHours,
                                             Boolean chargeable, String note) {
        authService.requireRole(user, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN);
        ReworkRecord rework = getOwnedRework(user, reworkId);
        if (rework.getStatus() != ReworkStatus.AWAIT_CONFIRM) {
            throw new BizException("返工单当前状态[" + rework.getStatus().getLabel() + "]，不能确认");
        }
        if (deadlineHours == null || deadlineHours <= 0) {
            throw new BizException("请确认返工完成时限 deadlineHours（正整数小时）");
        }
        if (chargeable == null) {
            throw new BizException("请确认本次返工是否收费 chargeable（true 收费重做 / false 免费返修）");
        }
        rework.setClinicAccepted(true);
        rework.setStatus(ReworkStatus.CONFIRMED);
        rework.setConfirmedBy(user);
        rework.setConfirmedAt(LocalDateTime.now());
        rework.setDeadlineHours(deadlineHours);
        rework.setDeadlineAt(LocalDateTime.now().plusHours(deadlineHours));
        rework.setChargeable(chargeable);
        rework.setConfirmNote(note);
        reworkRepo.save(rework);

        // 确认后牙位才正式打回，从模型接收重新流转
        ToothItem item = rework.getToothItem();
        item.setStatus(ToothItemStatus.REWORKING);
        item.setCurrentStage(null);
        item.setUpdatedAt(LocalDateTime.now());
        itemRepo.save(item);
        DentureOrder order = rework.getOrder();
        if (order.getStatus() != OrderStatus.IN_PRODUCTION) {
            order.setStatus(OrderStatus.IN_PRODUCTION);
            order.setUpdatedAt(LocalDateTime.now());
            orderRepo.save(order);
        }
        return Views.rework(rework);
    }

    /** 诊所有异议时驳回返工（需线下协商后重新登记，责任不清可上报异常）。 */
    @Transactional
    public Map<String, Object> clinicReject(Account user, Long reworkId, String reason) {
        authService.requireRole(user, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN);
        ReworkRecord rework = getOwnedRework(user, reworkId);
        if (rework.getStatus() != ReworkStatus.AWAIT_CONFIRM) {
            throw new BizException("返工单当前状态[" + rework.getStatus().getLabel() + "]，不能驳回");
        }
        if (reason == null || reason.isBlank()) {
            throw new BizException("请填写驳回原因 rejectReason");
        }
        rework.setStatus(ReworkStatus.REJECTED);
        rework.setClinicAccepted(false);
        rework.setConfirmedBy(user);
        rework.setConfirmedAt(LocalDateTime.now());
        rework.setRejectReason(reason);
        reworkRepo.save(rework);
        return Views.rework(rework);
    }

    /** 返工确认后，技工所标记原因三分类：设计问题 / 加工问题 / 医生取模问题。 */
    @Transactional
    public Map<String, Object> markCause(Account user, Long reworkId, ReworkCause cause, String note) {
        authService.requireRole(user, Role.LAB_ADMIN, Role.QC_INSPECTOR);
        ReworkRecord rework = reworkRepo.findById(reworkId)
                .orElseThrow(() -> BizException.notFound("返工单#" + reworkId));
        if (rework.getStatus() != ReworkStatus.CONFIRMED && rework.getStatus() != ReworkStatus.COMPLETED) {
            throw new BizException("返工单需诊所处确认后才能标记原因，当前状态: "
                    + rework.getStatus().getLabel());
        }
        rework.setCause(cause);
        rework.setCauseNote(note);
        rework.setCauseMarkedBy(user);
        rework.setCauseMarkedAt(LocalDateTime.now());
        reworkRepo.save(rework);
        return Views.rework(rework);
    }

    /** 判定返工责任并核定返工成本，自动计算双方分摊（月结按责任调整费用）。 */
    @Transactional
    public Map<String, Object> assignResponsibility(Account user, Long reworkId,
                                                    Responsibility responsibility, BigDecimal reworkCost) {
        authService.requireRole(user, Role.LAB_ADMIN, Role.CUSTOMER_SERVICE);
        ReworkRecord rework = reworkRepo.findById(reworkId)
                .orElseThrow(() -> BizException.notFound("返工单#" + reworkId));
        if (rework.getStatus() == ReworkStatus.AWAIT_CONFIRM || rework.getStatus() == ReworkStatus.REJECTED) {
            throw new BizException("返工单尚未经诊所确认，不能判定责任");
        }
        if (rework.getCause() == null) {
            throw new BizException("技工所尚未标记返工原因（设计问题/加工问题/医生取模问题），不能判定责任");
        }
        if (responsibility == Responsibility.PENDING) {
            throw new BizException("请将责任判定为 诊所/技工所/双方共担");
        }
        if (reworkCost == null || reworkCost.signum() < 0) {
            throw new BizException("返工成本 reworkCost 必须为非负数");
        }
        rework.setResponsibility(responsibility);
        rework.setReworkCost(reworkCost);
        switch (responsibility) {
            case CLINIC -> {
                rework.setClinicShare(reworkCost);
                rework.setLabShare(BigDecimal.ZERO);
            }
            case LAB -> {
                rework.setClinicShare(BigDecimal.ZERO);
                rework.setLabShare(reworkCost);
            }
            case SHARED -> {
                BigDecimal half = reworkCost.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
                rework.setClinicShare(half);
                rework.setLabShare(reworkCost.subtract(half));
            }
            default -> throw new BizException("非法责任类型");
        }
        reworkRepo.save(rework);
        return Views.rework(rework);
    }

    /** 诊所对账前复核返工。 */
    @Transactional
    public Map<String, Object> clinicReview(Account user, Long reworkId) {
        authService.requireRole(user, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN);
        ReworkRecord rework = getOwnedRework(user, reworkId);
        if (rework.getStatus() == ReworkStatus.REJECTED) {
            throw new BizException("该返工已被诊所驳回，不能复核");
        }
        if (rework.getResponsibility() == Responsibility.PENDING) {
            throw new BizException("返工责任尚未判定，诊所不能复核");
        }
        rework.setClinicReviewed(true);
        rework.setReviewedBy(user);
        rework.setReviewedAt(LocalDateTime.now());
        reworkRepo.save(rework);
        return Views.rework(rework);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long reworkId) {
        ReworkRecord rework = reworkRepo.findById(reworkId)
                .orElseThrow(() -> BizException.notFound("返工单#" + reworkId));
        return Views.rework(rework);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listByOrder(Long orderId) {
        return reworkRepo.findByOrderIdOrderByIdAsc(orderId).stream()
                .map(Views::rework).collect(Collectors.toList());
    }

    /** 诊所待复核返工列表（对账前复核入口）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listUnreviewed(Account clinicUser) {
        authService.requireRole(clinicUser, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN);
        if (clinicUser.getClinic() == null) {
            throw new BizException("当前账号未关联诊所");
        }
        List<DentureOrder> orders = orderRepo.findByClinicIdOrderByIdDesc(clinicUser.getClinic().getId());
        List<Long> orderIds = orders.stream().map(DentureOrder::getId).collect(Collectors.toList());
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return reworkRepo.findByOrderIdIn(orderIds).stream()
                .filter(r -> !r.isClinicReviewed() && r.getStatus() != ReworkStatus.REJECTED)
                .map(Views::rework).collect(Collectors.toList());
    }

    /** 诊所待确认返工列表。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAwaitingConfirm(Account clinicUser) {
        authService.requireRole(clinicUser, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN);
        if (clinicUser.getClinic() == null) {
            throw new BizException("当前账号未关联诊所");
        }
        List<Long> orderIds = orderRepo.findByClinicIdOrderByIdDesc(clinicUser.getClinic().getId()).stream()
                .map(DentureOrder::getId).collect(Collectors.toList());
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return reworkRepo.findByOrderIdIn(orderIds).stream()
                .filter(r -> r.getStatus() == ReworkStatus.AWAIT_CONFIRM)
                .map(Views::rework).collect(Collectors.toList());
    }

    // ---- 内部 ----

    private ReworkRecord getOwnedRework(Account user, Long reworkId) {
        ReworkRecord rework = reworkRepo.findById(reworkId)
                .orElseThrow(() -> BizException.notFound("返工单#" + reworkId));
        if (user.getClinic() == null
                || !user.getClinic().getId().equals(rework.getOrder().getClinic().getId())) {
            throw BizException.forbidden("只能处理本诊所订单的返工");
        }
        return rework;
    }

    private String buildReason(ReworkSource source, MisfitType misfitType, String detail) {
        String prefix = source.getLabel();
        if (misfitType != null) {
            prefix += "（" + misfitType.getLabel() + "）";
        }
        return prefix + ": " + (detail == null || detail.isBlank() ? "需返工重做" : detail);
    }

    /** 返工前该牙位各工序操作人/完成时间快照，作为“技工操作”证据随返工单留痕。 */
    private String snapshotTechnicianOps(ToothItem item) {
        return stageRepo.findByToothItemIdOrderByIdAsc(item.getId()).stream()
                .map(s -> s.getStage().getLabel() + ":" + s.getOperator().getDisplayName()
                        + "@" + (s.getFinishedAt() != null ? TS.format(s.getFinishedAt()) : TS.format(s.getStartedAt())))
                .collect(Collectors.joining(" | "));
    }
}
