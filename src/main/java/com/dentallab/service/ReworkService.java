package com.dentallab.service;

import com.dentallab.config.BizException;
import com.dentallab.model.*;
import com.dentallab.repo.DentureOrderRepo;
import com.dentallab.repo.ReworkRecordRepo;
import com.dentallab.repo.ToothItemRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 试戴不密合返工：
 * 自动建单（待方案）→ 技工所提交返工方案（返工时限/责任/是否收费/技工操作）→ 诊所确认或驳回；
 * 确认后技工重做，并标记返工原因（设计问题/加工问题/医生取模问题），月结按责任调整费用；
 * 诊所对账前逐条复核。
 */
@Service
public class ReworkService {

    private final ReworkRecordRepo reworkRepo;
    private final DentureOrderRepo orderRepo;
    private final ToothItemRepo itemRepo;
    private final AuthService authService;

    public ReworkService(ReworkRecordRepo reworkRepo, DentureOrderRepo orderRepo,
                         ToothItemRepo itemRepo, AuthService authService) {
        this.reworkRepo = reworkRepo;
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.authService = authService;
    }

    /** 手动创建返工单（如配送破损、质检发现）：牙位打回，进入“待技工所提交返工方案”。 */
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
        if (!reworkRepo.findByToothItemIdAndStatusNot(toothItemId, ReworkStatus.COMPLETED).isEmpty()) {
            throw new BizException("牙位 " + item.getToothPosition() + " 已有进行中的返工单");
        }
        ReworkRecord rework = new ReworkRecord();
        rework.setOrder(order);
        rework.setToothItem(item);
        rework.setReason(reason);
        rework.setStatus(ReworkStatus.AWAITING_PLAN);
        reworkRepo.save(rework);

        item.setStatus(ToothItemStatus.REWORKING);
        item.setCurrentStage(null);
        item.setUpdatedAt(LocalDateTime.now());
        itemRepo.save(item);
        if (order.getStatus() != OrderStatus.IN_PRODUCTION) {
            order.setStatus(OrderStatus.IN_PRODUCTION);
            order.setUpdatedAt(LocalDateTime.now());
            orderRepo.save(order);
        }
        return Views.rework(rework);
    }

    /**
     * 技工所提交返工方案：返工时限（小时）、建议责任、返工成本、是否向诊所收费、技工操作说明。
     * 提交后状态为“待诊所确认”。
     */
    @Transactional
    public Map<String, Object> submitPlan(Account user, Long reworkId, Integer deadlineHours,
                                          Responsibility proposed, BigDecimal reworkCost,
                                          boolean chargeClinic, String labAction) {
        authService.requireRole(user, Role.LAB_ADMIN, Role.LAB_RECEIVER, Role.CUSTOMER_SERVICE);
        ReworkRecord rework = mustGet(reworkId);
        if (rework.getStatus() != ReworkStatus.AWAITING_PLAN && rework.getStatus() != ReworkStatus.REJECTED) {
            throw new BizException("当前返工单状态[" + rework.getStatus().getLabel() + "]不能提交/修订返工方案");
        }
        if (deadlineHours == null || deadlineHours <= 0) {
            throw new BizException("返工时限 reworkDeadlineHours 必须为正整数（小时）");
        }
        if (proposed == null || proposed == Responsibility.PENDING) {
            throw new BizException("返工方案必须给出建议责任 proposedResponsibility：CLINIC/LAB/SHARED");
        }
        if (reworkCost == null || reworkCost.signum() < 0) {
            throw new BizException("返工成本 reworkCost 必须为非负数");
        }
        rework.setReworkDeadlineHours(deadlineHours);
        rework.setProposedResponsibility(proposed);
        rework.setReworkCost(reworkCost);
        rework.setChargeClinic(chargeClinic);
        rework.setLabAction(labAction);
        rework.setPlannedBy(user);
        rework.setPlanSubmittedAt(LocalDateTime.now());
        rework.setRejectReason(null);
        applyShares(rework, proposed, reworkCost, chargeClinic);
        rework.setStatus(ReworkStatus.AWAITING_CONFIRM);
        reworkRepo.save(rework);
        return Views.rework(rework);
    }

    /**
     * 诊所确认返工方案：确认返工时限、责任判断与是否收费；
     * 确认后责任落定，牙位进入返工重做。
     */
    @Transactional
    public Map<String, Object> clinicConfirm(Account user, Long reworkId) {
        authService.requireRole(user, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN);
        ReworkRecord rework = mustGet(reworkId);
        ensureClinic(user, rework);
        if (rework.getStatus() != ReworkStatus.AWAITING_CONFIRM) {
            throw new BizException("返工方案未提交或已处理，当前状态: " + rework.getStatus().getLabel());
        }
        rework.setStatus(ReworkStatus.IN_REWORK);
        rework.setResponsibility(rework.getProposedResponsibility());
        rework.setClinicConfirmed(true);
        rework.setConfirmedBy(user);
        rework.setConfirmedAt(LocalDateTime.now());
        reworkRepo.save(rework);

        // 牙位打回生产线，从模型接收重新流转
        ToothItem item = rework.getToothItem();
        item.setStatus(ToothItemStatus.REWORKING);
        item.setCurrentStage(null);
        item.setUpdatedAt(LocalDateTime.now());
        itemRepo.save(item);
        return Views.rework(rework);
    }

    /** 诊所驳回返工方案（对时限/责任/收费有异议），技工所需修订后重新提交。 */
    @Transactional
    public Map<String, Object> clinicReject(Account user, Long reworkId, String reason) {
        authService.requireRole(user, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN);
        ReworkRecord rework = mustGet(reworkId);
        ensureClinic(user, rework);
        if (rework.getStatus() != ReworkStatus.AWAITING_CONFIRM) {
            throw new BizException("只能驳回待确认的返工方案，当前状态: " + rework.getStatus().getLabel());
        }
        if (reason == null || reason.isBlank()) {
            throw new BizException("驳回必须填写驳回原因 rejectReason");
        }
        rework.setStatus(ReworkStatus.REJECTED);
        rework.setRejectReason(reason);
        reworkRepo.save(rework);
        return Views.rework(rework);
    }

    /**
     * 返工方案确认后、重做质检环节，技工所标记返工原因：
     * 设计问题/加工问题=技工所责任；医生取模问题=诊所责任。按月结责任调整费用。
     */
    @Transactional
    public Map<String, Object> markCause(Account user, Long reworkId, ReworkCause cause) {
        authService.requireRole(user, Role.LAB_ADMIN, Role.QC_INSPECTOR, Role.TECHNICIAN, Role.LAB_RECEIVER);
        ReworkRecord rework = mustGet(reworkId);
        if (rework.getStatus() != ReworkStatus.IN_REWORK) {
            throw new BizException("返工方案未经诊所确认，不能标记返工原因，当前状态: "
                    + rework.getStatus().getLabel());
        }
        if (cause == null) {
            throw new BizException("返工原因 cause 必填：DESIGN(设计问题)/PROCESSING(加工问题)/IMPRESSION(医生取模问题)");
        }
        rework.setCause(cause);
        rework.setMarkedBy(user);
        rework.setCauseMarkedAt(LocalDateTime.now());
        // 按原因落定费用责任
        Responsibility byCause = switch (cause) {
            case DESIGN, PROCESSING -> Responsibility.LAB;
            case IMPRESSION -> Responsibility.CLINIC;
        };
        rework.setResponsibility(byCause);
        applyShares(rework, byCause, rework.getReworkCost(), cause == ReworkCause.IMPRESSION);
        rework.setChargeClinic(cause == ReworkCause.IMPRESSION);
        reworkRepo.save(rework);
        return Views.rework(rework);
    }

    /** 管理员/客服直接判定返工责任与成本（争议裁决等场景），自动计算双方分摊。 */
    @Transactional
    public Map<String, Object> assignResponsibility(Account user, Long reworkId,
                                                    Responsibility responsibility, BigDecimal reworkCost) {
        authService.requireRole(user, Role.LAB_ADMIN, Role.CUSTOMER_SERVICE);
        ReworkRecord rework = mustGet(reworkId);
        if (responsibility == null || responsibility == Responsibility.PENDING) {
            throw new BizException("请将责任判定为 诊所/技工所/双方共担");
        }
        if (reworkCost == null || reworkCost.signum() < 0) {
            throw new BizException("返工成本 reworkCost 必须为非负数");
        }
        rework.setResponsibility(responsibility);
        rework.setProposedResponsibility(responsibility);
        rework.setReworkCost(reworkCost);
        applyShares(rework, responsibility, reworkCost, responsibility == Responsibility.CLINIC);
        reworkRepo.save(rework);
        return Views.rework(rework);
    }

    /** 诊所对账前复核返工（须责任已落定）。 */
    @Transactional
    public Map<String, Object> clinicReview(Account user, Long reworkId) {
        authService.requireRole(user, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN);
        ReworkRecord rework = mustGet(reworkId);
        ensureClinic(user, rework);
        if (rework.getResponsibility() == null) {
            throw new BizException("返工责任尚未判定（返工方案未确认），诊所不能复核");
        }
        rework.setClinicReviewed(true);
        rework.setReviewedBy(user);
        rework.setReviewedAt(LocalDateTime.now());
        reworkRepo.save(rework);
        return Views.rework(rework);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listByOrder(Long orderId) {
        return reworkRepo.findByOrderIdOrderByIdAsc(orderId).stream()
                .map(Views::rework).collect(Collectors.toList());
    }

    /** 技工所工作台：待提交/被驳回待修订方案的返工单。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAwaitingPlan(Account user) {
        authService.requireRole(user, Role.LAB_ADMIN, Role.LAB_RECEIVER, Role.CUSTOMER_SERVICE, Role.FINANCE);
        return reworkRepo.findAll().stream()
                .filter(r -> r.getStatus() == ReworkStatus.AWAITING_PLAN || r.getStatus() == ReworkStatus.REJECTED)
                .map(Views::rework).collect(Collectors.toList());
    }

    /** 诊所工作台：待本诊所确认返工方案的返工单。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAwaitingConfirm(Account clinicUser) {
        authService.requireRole(clinicUser, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN);
        if (clinicUser.getClinic() == null) {
            throw new BizException("当前账号未关联诊所");
        }
        return reworkRepo.findAll().stream()
                .filter(r -> r.getStatus() == ReworkStatus.AWAITING_CONFIRM)
                .filter(r -> r.getOrder().getClinic().getId().equals(clinicUser.getClinic().getId()))
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
                .filter(r -> !r.isClinicReviewed())
                .map(Views::rework).collect(Collectors.toList());
    }

    // ---- 内部 ----

    private ReworkRecord mustGet(Long reworkId) {
        return reworkRepo.findById(reworkId)
                .orElseThrow(() -> BizException.notFound("返工单#" + reworkId));
    }

    private void ensureClinic(Account user, ReworkRecord rework) {
        if (user.getClinic() == null
                || !user.getClinic().getId().equals(rework.getOrder().getClinic().getId())) {
            throw BizException.forbidden("只能操作本诊所订单的返工");
        }
    }

    /** 按责任与是否收费计算分摊；SHARED 五五分（诊所部分以收费为前提）。 */
    private void applyShares(ReworkRecord rework, Responsibility responsibility,
                             BigDecimal reworkCost, boolean chargeClinic) {
        switch (responsibility) {
            case CLINIC -> {
                rework.setClinicShare(chargeClinic ? reworkCost : BigDecimal.ZERO);
                rework.setLabShare(BigDecimal.ZERO);
            }
            case LAB -> {
                rework.setClinicShare(BigDecimal.ZERO);
                rework.setLabShare(reworkCost);
            }
            case SHARED -> {
                BigDecimal half = reworkCost.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
                rework.setClinicShare(chargeClinic ? half : BigDecimal.ZERO);
                rework.setLabShare(reworkCost.subtract(half));
            }
            default -> throw new BizException("非法责任类型");
        }
    }
}
