package com.dentallab.service;

import com.dentallab.config.BizException;
import com.dentallab.model.*;
import com.dentallab.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 诊所月结三方协同：
 * 财务生成月结单 → 技工所同步给诊所 → 诊所确认（账期内返工须全部复核）→ 财务端确认；
 * 诊所有异议可发起月结争议（同步记录），技工所/财务处理后重新同步诊所再确认。
 */
@Service
public class StatementService {

    private final MonthlyStatementRepo statementRepo;
    private final DentureOrderRepo orderRepo;
    private final SettlementRepo settlementRepo;
    private final ReworkRecordRepo reworkRepo;
    private final StatementDisputeRepo disputeRepo;
    private final ClinicRepo clinicRepo;
    private final AuthService authService;

    public StatementService(MonthlyStatementRepo statementRepo, DentureOrderRepo orderRepo,
                            SettlementRepo settlementRepo, ReworkRecordRepo reworkRepo,
                            StatementDisputeRepo disputeRepo, ClinicRepo clinicRepo,
                            AuthService authService) {
        this.statementRepo = statementRepo;
        this.orderRepo = orderRepo;
        this.settlementRepo = settlementRepo;
        this.reworkRepo = reworkRepo;
        this.disputeRepo = disputeRepo;
        this.clinicRepo = clinicRepo;
        this.authService = authService;
    }

    /** 财务生成诊所月结单（按订单完成时间归属账期，需订单已结算）。 */
    @Transactional
    public Map<String, Object> generate(Account finance, Long clinicId, String yearMonth) {
        authService.requireRole(finance, Role.FINANCE, Role.LAB_ADMIN);
        Clinic clinic = clinicRepo.findById(clinicId)
                .orElseThrow(() -> BizException.notFound("诊所#" + clinicId));
        YearMonth ym = parseYearMonth(yearMonth);
        if (statementRepo.findByClinicIdAndYearMonth(clinicId, ym.toString()).isPresent()) {
            throw new BizException("诊所[" + clinic.getName() + "] " + ym + " 月结单已存在");
        }
        List<DentureOrder> orders = periodOrders(clinicId, ym);
        List<Long> orderIds = orders.stream().map(DentureOrder::getId).collect(Collectors.toList());

        List<Settlement> settlements = orderIds.isEmpty() ? List.of() : settlementRepo.findByOrderIdIn(orderIds);
        if (settlements.size() < orderIds.size()) {
            long missing = orderIds.size() - settlements.size();
            throw new BizException("账期内还有 " + missing + " 个已完成订单未生成结算单，请先生成结算");
        }
        BigDecimal total = settlements.stream().map(Settlement::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<ReworkRecord> reworks = orderIds.isEmpty() ? List.of() : reworkRepo.findByOrderIdIn(orderIds);
        long unreviewed = reworks.stream().filter(r -> !r.isClinicReviewed()).count();

        MonthlyStatement statement = new MonthlyStatement();
        statement.setClinic(clinic);
        statement.setYearMonth(ym.toString());
        statement.setOrderCount(orders.size());
        statement.setTotalAmount(total);
        statement.setReworkClinicShare(sumShare(reworks, true));
        statement.setReworkLabDeduction(sumShare(reworks, false));
        statement.setUnreviewedReworkCount((int) unreviewed);
        statement.setStatus(StatementStatus.GENERATED);
        statementRepo.save(statement);
        return Views.statement(statement);
    }

    /** 技工所把月结单同步给诊所（诊所可见后方可确认/争议）。 */
    @Transactional
    public Map<String, Object> syncToClinic(Account user, Long statementId) {
        authService.requireRole(user, Role.LAB_ADMIN, Role.LAB_RECEIVER, Role.FINANCE);
        MonthlyStatement statement = mustGet(statementId);
        if (statement.getStatus() == StatementStatus.FINANCE_CONFIRMED) {
            throw new BizException("月结单已财务确认，无需同步");
        }
        if (statement.getStatus() != StatementStatus.GENERATED && statement.getStatus() != StatementStatus.DISPUTED) {
            throw new BizException("当前月结单状态[" + statement.getStatus().getLabel() + "]不能同步");
        }
        statement.setStatus(StatementStatus.SYNCED);
        statement.setSyncedBy(user);
        statement.setSyncedAt(LocalDateTime.now());
        statementRepo.save(statement);
        return detail(statementId);
    }

    /** 诊所确认月结：账期内全部返工必须已复核。 */
    @Transactional
    public Map<String, Object> confirm(Account clinicUser, Long statementId) {
        authService.requireRole(clinicUser, Role.CLINIC_ADMIN);
        MonthlyStatement statement = mustGet(statementId);
        ensureOwnClinic(clinicUser, statement);
        if (statement.getStatus() == StatementStatus.FINANCE_CONFIRMED) {
            throw new BizException("月结单已财务确认，不能重复确认");
        }
        if (statement.getStatus() != StatementStatus.SYNCED) {
            throw new BizException("月结单尚未同步到诊所或存在未处理争议，当前状态: "
                    + statement.getStatus().getLabel());
        }
        long unreviewed = countUnreviewed(statement);
        statement.setUnreviewedReworkCount((int) unreviewed);
        if (unreviewed > 0) {
            statementRepo.save(statement);
            throw new BizException("账期内还有 " + unreviewed + " 条返工未复核，诊所对账前必须复核全部返工");
        }
        statement.setStatus(StatementStatus.CLINIC_CONFIRMED);
        statement.setConfirmedAt(LocalDateTime.now());
        statementRepo.save(statement);
        return Views.statement(statement);
    }

    /** 财务端确认月结（诊所确认之后）。 */
    @Transactional
    public Map<String, Object> financeConfirm(Account finance, Long statementId) {
        authService.requireRole(finance, Role.FINANCE, Role.LAB_ADMIN);
        MonthlyStatement statement = mustGet(statementId);
        if (statement.getStatus() != StatementStatus.CLINIC_CONFIRMED) {
            throw new BizException("只有诊所已确认的月结单才能财务确认，当前状态: "
                    + statement.getStatus().getLabel());
        }
        statement.setStatus(StatementStatus.FINANCE_CONFIRMED);
        statement.setFinanceConfirmedBy(finance);
        statement.setFinanceConfirmedAt(LocalDateTime.now());
        statementRepo.save(statement);
        return Views.statement(statement);
    }

    /** 诊所对月结单提出争议（月结争议同步记录）。 */
    @Transactional
    public Map<String, Object> raiseDispute(Account clinicUser, Long statementId, String reason, Long reworkId) {
        authService.requireRole(clinicUser, Role.CLINIC_ADMIN, Role.CLINIC_DOCTOR);
        MonthlyStatement statement = mustGet(statementId);
        ensureOwnClinic(clinicUser, statement);
        if (reason == null || reason.isBlank()) {
            throw new BizException("月结争议必须填写原因 reason");
        }
        if (statement.getStatus() != StatementStatus.SYNCED) {
            throw new BizException("只有技工所已同步、待诊所确认的月结单可以提出争议，当前状态: "
                    + statement.getStatus().getLabel());
        }
        StatementDispute dispute = new StatementDispute();
        dispute.setStatement(statement);
        dispute.setReason(reason);
        dispute.setRaisedBy(clinicUser);
        if (reworkId != null) {
            ReworkRecord rework = reworkRepo.findById(reworkId)
                    .orElseThrow(() -> BizException.notFound("返工单#" + reworkId));
            if (!rework.getOrder().getClinic().getId().equals(statement.getClinic().getId())) {
                throw new BizException("返工单#" + reworkId + "不属于该月结单诊所");
            }
            dispute.setRework(rework);
        }
        disputeRepo.save(dispute);
        statement.setStatus(StatementStatus.DISPUTED);
        statement.setDisputeCount((int) disputeRepo.countByStatementId(statementId));
        statementRepo.save(statement);
        return Views.dispute(dispute);
    }

    /** 技工所/财务处理月结争议：解决后月结单重新同步给诊所。 */
    @Transactional
    public Map<String, Object> resolveDispute(Account user, Long disputeId, String resolution) {
        authService.requireRole(user, Role.LAB_ADMIN, Role.CUSTOMER_SERVICE, Role.FINANCE);
        StatementDispute dispute = disputeRepo.findById(disputeId)
                .orElseThrow(() -> BizException.notFound("月结争议#" + disputeId));
        if (dispute.getStatus() == StatementDisputeStatus.RESOLVED) {
            throw new BizException("该月结争议已处理");
        }
        if (resolution == null || resolution.isBlank()) {
            throw new BizException("处理月结争议必须填写处理结论 resolution");
        }
        dispute.setStatus(StatementDisputeStatus.RESOLVED);
        dispute.setResolvedBy(user);
        dispute.setResolution(resolution);
        dispute.setResolvedAt(LocalDateTime.now());
        disputeRepo.save(dispute);

        MonthlyStatement statement = dispute.getStatement();
        if (disputeRepo.findByStatementIdOrderByIdAsc(statement.getId()).stream()
                .allMatch(d -> d.getStatus() == StatementDisputeStatus.RESOLVED)) {
            // 全部争议解决：重新同步诊所（回到已同步待确认，保留原同步痕迹）
            statement.setStatus(StatementStatus.SYNCED);
            statement.setSyncedBy(user);
            statement.setSyncedAt(LocalDateTime.now());
            statementRepo.save(statement);
        }
        return Views.dispute(dispute);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Account user, Long clinicId) {
        Long effectiveClinicId = clinicId;
        if (user.getRole() == Role.CLINIC_ADMIN || user.getRole() == Role.CLINIC_DOCTOR) {
            if (user.getClinic() == null) {
                throw new BizException("当前账号未关联诊所");
            }
            effectiveClinicId = user.getClinic().getId();
        }
        if (effectiveClinicId == null) {
            throw new BizException("请指定 clinicId");
        }
        return statementRepo.findByClinicIdOrderByYearMonthDesc(effectiveClinicId).stream()
                .map(Views::statement).collect(Collectors.toList());
    }

    /** 月结单详情：含争议记录与账期内返工调整明细。 */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long statementId) {
        MonthlyStatement statement = mustGet(statementId);
        Map<String, Object> m = Views.statement(statement);
        m.put("disputes", disputeRepo.findByStatementIdOrderByIdAsc(statementId).stream()
                .map(Views::dispute).collect(Collectors.toList()));
        YearMonth ym = parseYearMonth(statement.getYearMonth());
        List<Long> orderIds = periodOrders(statement.getClinic().getId(), ym).stream()
                .map(DentureOrder::getId).collect(Collectors.toList());
        m.put("reworks", orderIds.isEmpty() ? List.of()
                : reworkRepo.findByOrderIdIn(orderIds).stream().map(Views::rework).collect(Collectors.toList()));
        return m;
    }

    // ---- 内部 ----

    private MonthlyStatement mustGet(Long id) {
        return statementRepo.findById(id)
                .orElseThrow(() -> BizException.notFound("月结单#" + id));
    }

    private void ensureOwnClinic(Account user, MonthlyStatement statement) {
        if (user.getClinic() == null
                || !user.getClinic().getId().equals(statement.getClinic().getId())) {
            throw BizException.forbidden("只能操作本诊所的月结单");
        }
    }

    private List<DentureOrder> periodOrders(Long clinicId, YearMonth ym) {
        LocalDateTime from = ym.atDay(1).atStartOfDay();
        LocalDateTime to = ym.plusMonths(1).atDay(1).atStartOfDay();
        return orderRepo.findByClinicIdAndCompletedAtBetween(clinicId, from, to);
    }

    private long countUnreviewed(MonthlyStatement statement) {
        YearMonth ym = parseYearMonth(statement.getYearMonth());
        List<Long> orderIds = periodOrders(statement.getClinic().getId(), ym).stream()
                .map(DentureOrder::getId).collect(Collectors.toList());
        return orderIds.isEmpty() ? 0 : reworkRepo.findByOrderIdIn(orderIds).stream()
                .filter(r -> !r.isClinicReviewed()).count();
    }

    private BigDecimal sumShare(List<ReworkRecord> reworks, boolean clinicSide) {
        return reworks.stream()
                .map(r -> clinicSide ? r.getClinicShare() : r.getLabShare())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private YearMonth parseYearMonth(String yearMonth) {
        try {
            return YearMonth.parse(yearMonth);
        } catch (Exception e) {
            throw new BizException("账期格式应为 yyyy-MM: " + yearMonth);
        }
    }
}
