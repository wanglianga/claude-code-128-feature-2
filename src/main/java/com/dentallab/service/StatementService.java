package com.dentallab.service;

import com.dentallab.config.BizException;
import com.dentallab.model.*;
import com.dentallab.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 诊所月结：财务按账期汇总已结算订单与返工责任调整 → 技工所同步 → 同步诊所 →
 * 诊所确认（账期内返工须全部复核）→ 财务最终确认；诊所/技工所任一方有异议可登记月结争议。
 */
@Service
public class StatementService {

    private final MonthlyStatementRepo statementRepo;
    private final StatementDisputeRepo disputeRepo;
    private final DentureOrderRepo orderRepo;
    private final SettlementRepo settlementRepo;
    private final ReworkRecordRepo reworkRepo;
    private final ClinicRepo clinicRepo;
    private final AuthService authService;

    public StatementService(MonthlyStatementRepo statementRepo, StatementDisputeRepo disputeRepo,
                            DentureOrderRepo orderRepo, SettlementRepo settlementRepo,
                            ReworkRecordRepo reworkRepo, ClinicRepo clinicRepo, AuthService authService) {
        this.statementRepo = statementRepo;
        this.disputeRepo = disputeRepo;
        this.orderRepo = orderRepo;
        this.settlementRepo = settlementRepo;
        this.reworkRepo = reworkRepo;
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
        PeriodData data = loadPeriod(clinicId, ym);
        if (data.settlements.size() < data.orderIds.size()) {
            long missing = data.orderIds.size() - data.settlements.size();
            throw new BizException("账期内还有 " + missing + " 个已完成订单未生成结算单，请先生成结算");
        }
        BigDecimal total = data.settlements.stream().map(Settlement::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        MonthlyStatement statement = new MonthlyStatement();
        statement.setClinic(clinic);
        statement.setYearMonth(ym.toString());
        statement.setOrderCount(data.orders.size());
        statement.setTotalAmount(total);
        statement.setReworkClinicShare(data.clinicShare);
        statement.setReworkLabDeduction(data.labShare);
        statement.setUnreviewedReworkCount((int) data.unreviewed);
        statementRepo.save(statement);
        return Views.statement(statement);
    }

    /** 技工所同步月结单：确认单据并同步给诊所（GENERATED → LAB_SYNCED）。 */
    @Transactional
    public Map<String, Object> labSync(Account user, Long statementId) {
        authService.requireRole(user, Role.LAB_ADMIN, Role.FINANCE, Role.LAB_RECEIVER);
        MonthlyStatement statement = getStatement(statementId);
        if (statement.getStatus() != StatementStatus.GENERATED) {
            throw new BizException("月结单当前状态[" + statement.getStatus().getLabel()
                    + "]，只有已生成待同步的月结单可同步");
        }
        LocalDateTime now = LocalDateTime.now();
        statement.setStatus(StatementStatus.LAB_SYNCED);
        statement.setLabSyncedAt(now);
        statement.setClinicSyncedAt(now);
        statementRepo.save(statement);
        return Views.statement(statement);
    }

    /** 诊所确认月结：必须已由技工所同步，且账期内全部返工已复核。 */
    @Transactional
    public Map<String, Object> confirm(Account clinicUser, Long statementId) {
        authService.requireRole(clinicUser, Role.CLINIC_ADMIN, Role.CLINIC_DOCTOR);
        MonthlyStatement statement = getStatement(statementId);
        assertClinicOwned(clinicUser, statement);
        if (statement.getStatus() == StatementStatus.CLINIC_CONFIRMED
                || statement.getStatus() == StatementStatus.FINANCE_CONFIRMED) {
            throw new BizException("月结单已确认，不能重复确认");
        }
        if (statement.getStatus() != StatementStatus.LAB_SYNCED) {
            throw new BizException("技工所尚未同步本月结单，诊所不能确认");
        }
        long unreviewed = refreshUnreviewed(statement);
        if (unreviewed > 0) {
            throw new BizException("账期内还有 " + unreviewed + " 条返工未复核，诊所对账前必须复核全部返工");
        }
        statement.setStatus(StatementStatus.CLINIC_CONFIRMED);
        statement.setConfirmedAt(LocalDateTime.now());
        statementRepo.save(statement);
        return Views.statement(statement);
    }

    /** 财务端最终确认：诊所确认后进行；存在未解决月结争议时不能确认。 */
    @Transactional
    public Map<String, Object> financeConfirm(Account finance, Long statementId) {
        authService.requireRole(finance, Role.FINANCE, Role.LAB_ADMIN);
        MonthlyStatement statement = getStatement(statementId);
        if (statement.getStatus() == StatementStatus.FINANCE_CONFIRMED) {
            throw new BizException("月结单已财务确认，不能重复确认");
        }
        if (statement.getStatus() != StatementStatus.CLINIC_CONFIRMED) {
            throw new BizException("诊所尚未确认月结单，财务不能最终确认，当前状态: "
                    + statement.getStatus().getLabel());
        }
        long openDisputes = disputeRepo.countByStatementIdAndStatus(statementId, DisputeStatus.OPEN);
        if (openDisputes > 0) {
            throw new BizException("月结单存在 " + openDisputes + " 条未解决争议，处理完后才能财务确认");
        }
        statement.setStatus(StatementStatus.FINANCE_CONFIRMED);
        statement.setFinanceConfirmedAt(LocalDateTime.now());
        statement.setFinanceConfirmedBy(finance);
        statementRepo.save(statement);
        return Views.statement(statement);
    }

    /** 月结单详情：账期订单结算、返工责任调整、争议记录。 */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Account user, Long statementId) {
        MonthlyStatement statement = getStatement(statementId);
        if (user.getRole() == Role.CLINIC_ADMIN || user.getRole() == Role.CLINIC_DOCTOR) {
            assertClinicOwned(user, statement);
            assertSyncedToClinic(statement);
        }
        Map<String, Object> m = Views.statement(statement);
        YearMonth ym = parseYearMonth(statement.getYearMonth());
        PeriodData data = loadPeriod(statement.getClinic().getId(), ym);

        List<Map<String, Object>> orderLines = new ArrayList<>();
        for (Settlement s : data.settlements) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("orderId", s.getOrder().getId());
            line.put("orderNo", s.getOrder().getOrderNo());
            line.put("baseAmount", s.getBaseAmount());
            line.put("rushFee", s.getRushFee());
            line.put("reworkClinicShare", s.getReworkClinicShare());
            line.put("reworkLabDeduction", s.getReworkLabDeduction());
            line.put("deliveryPenalty", s.getDeliveryPenalty());
            line.put("totalAmount", s.getTotalAmount());
            orderLines.add(line);
        }
        m.put("orders", orderLines);
        m.put("reworks", data.reworks.stream().map(r -> {
            Map<String, Object> rw = Views.rework(r);
            return rw;
        }).collect(Collectors.toList()));
        m.put("disputes", disputeRepo.findByStatementIdOrderByIdAsc(statementId).stream()
                .map(Views::dispute).collect(Collectors.toList()));
        return m;
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
        List<MonthlyStatement> statements = statementRepo.findByClinicIdOrderByYearMonthDesc(effectiveClinicId);
        // 诊所只能看到技工所已同步（LAB_SYNCED 及之后）的月结单
        if (user.getRole() == Role.CLINIC_ADMIN || user.getRole() == Role.CLINIC_DOCTOR) {
            statements = statements.stream()
                    .filter(s -> s.getStatus() != StatementStatus.GENERATED)
                    .collect(Collectors.toList());
        }
        return statements.stream().map(Views::statement).collect(Collectors.toList());
    }

    // ---- 月结争议 ----

    /** 提出月结争议：诊所或技工所均可，财务确认前挂起。 */
    @Transactional
    public Map<String, Object> raiseDispute(Account user, Long statementId, String description) {
        if (description == null || description.isBlank()) {
            throw new BizException("请填写争议说明 description");
        }
        MonthlyStatement statement = getStatement(statementId);
        boolean clinicSide = user.getRole() == Role.CLINIC_ADMIN || user.getRole() == Role.CLINIC_DOCTOR;
        boolean labSide = user.getRole() == Role.LAB_ADMIN || user.getRole() == Role.CUSTOMER_SERVICE
                || user.getRole() == Role.FINANCE || user.getRole() == Role.LAB_RECEIVER;
        if (!clinicSide && !labSide) {
            throw BizException.forbidden("当前角色不能提出月结争议");
        }
        if (clinicSide) {
            assertClinicOwned(user, statement);
            assertSyncedToClinic(statement);
        }
        if (statement.getStatus() == StatementStatus.FINANCE_CONFIRMED) {
            throw new BizException("月结单已财务确认，不能再登记争议");
        }
        StatementDispute dispute = new StatementDispute();
        dispute.setStatement(statement);
        dispute.setRaisedSide(clinicSide ? "CLINIC" : "LAB");
        dispute.setRaisedBy(user);
        dispute.setDescription(description);
        disputeRepo.save(dispute);
        return Views.dispute(dispute);
    }

    /** 处理月结争议（诊所与技工所双方都可结案）。 */
    @Transactional
    public Map<String, Object> resolveDispute(Account user, Long disputeId, String resolution) {
        StatementDispute dispute = disputeRepo.findById(disputeId)
                .orElseThrow(() -> BizException.notFound("月结争议#" + disputeId));
        boolean clinicSide = user.getRole() == Role.CLINIC_ADMIN || user.getRole() == Role.CLINIC_DOCTOR;
        if (clinicSide) {
            assertClinicOwned(user, dispute.getStatement());
        } else {
            authService.requireRole(user, Role.LAB_ADMIN, Role.CUSTOMER_SERVICE, Role.FINANCE);
        }
        if (dispute.getStatus() == DisputeStatus.RESOLVED) {
            throw new BizException("争议已解决，不能重复处理");
        }
        dispute.setStatus(DisputeStatus.RESOLVED);
        dispute.setResolution(resolution == null || resolution.isBlank() ? "双方协商一致" : resolution);
        dispute.setResolvedBy(user);
        dispute.setResolvedAt(LocalDateTime.now());
        disputeRepo.save(dispute);
        return Views.dispute(dispute);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listDisputes(Account user, Long statementId) {
        MonthlyStatement statement = getStatement(statementId);
        boolean clinicSide = user.getRole() == Role.CLINIC_ADMIN || user.getRole() == Role.CLINIC_DOCTOR;
        if (clinicSide) {
            assertClinicOwned(user, statement);
            assertSyncedToClinic(statement);
        }
        return disputeRepo.findByStatementIdOrderByIdAsc(statementId).stream()
                .map(Views::dispute).collect(Collectors.toList());
    }

    /** 未解决争议工作台：诊所只看本诊所；技工所/财务看全部。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listOpenDisputes(Account user) {
        boolean clinicSide = user.getRole() == Role.CLINIC_ADMIN || user.getRole() == Role.CLINIC_DOCTOR;
        return disputeRepo.findAll().stream()
                .filter(d -> d.getStatus() == DisputeStatus.OPEN)
                .filter(d -> !clinicSide
                        || (user.getClinic() != null
                            && user.getClinic().getId().equals(d.getStatement().getClinic().getId())))
                .map(Views::dispute).collect(Collectors.toList());
    }

    // ---- 内部 ----

    private MonthlyStatement getStatement(Long id) {
        return statementRepo.findById(id).orElseThrow(() -> BizException.notFound("月结单#" + id));
    }

    private void assertClinicOwned(Account user, MonthlyStatement statement) {
        if (user.getClinic() == null
                || !user.getClinic().getId().equals(statement.getClinic().getId())) {
            throw BizException.forbidden("只能操作本诊所的月结单");
        }
    }

    private void assertSyncedToClinic(MonthlyStatement statement) {
        if (statement.getStatus() == StatementStatus.GENERATED) {
            throw new BizException("技工所尚未同步该月结单，诊所暂不可见");
        }
    }

    private long refreshUnreviewed(MonthlyStatement statement) {
        PeriodData data = loadPeriod(statement.getClinic().getId(), parseYearMonth(statement.getYearMonth()));
        statement.setUnreviewedReworkCount((int) data.unreviewed);
        statement.setReworkClinicShare(data.clinicShare);
        statement.setReworkLabDeduction(data.labShare);
        statementRepo.save(statement);
        return data.unreviewed;
    }

    private PeriodData loadPeriod(Long clinicId, YearMonth ym) {
        LocalDateTime from = ym.atDay(1).atStartOfDay();
        LocalDateTime to = ym.plusMonths(1).atDay(1).atStartOfDay();
        List<DentureOrder> orders = orderRepo.findByClinicIdAndCompletedAtBetween(clinicId, from, to);
        List<Long> orderIds = orders.stream().map(DentureOrder::getId).collect(Collectors.toList());
        List<Settlement> settlements = orderIds.isEmpty() ? List.of()
                : settlementRepo.findByOrderIdIn(orderIds);
        List<ReworkRecord> reworks = orderIds.isEmpty() ? List.of()
                : reworkRepo.findByOrderIdIn(orderIds);
        PeriodData data = new PeriodData();
        data.orders = orders;
        data.orderIds = orderIds;
        data.settlements = settlements;
        data.reworks = reworks;
        data.unreviewed = reworks.stream()
                .filter(r -> !r.isClinicReviewed() && r.getStatus() != ReworkStatus.REJECTED).count();
        data.clinicShare = reworks.stream().map(ReworkRecord::getClinicShare)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        data.labShare = reworks.stream().map(ReworkRecord::getLabShare)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return data;
    }

    private YearMonth parseYearMonth(String yearMonth) {
        try {
            return YearMonth.parse(yearMonth);
        } catch (Exception e) {
            throw new BizException("账期格式应为 yyyy-MM: " + yearMonth);
        }
    }

    /** 账期数据聚合载体。 */
    private static class PeriodData {
        private List<DentureOrder> orders;
        private List<Long> orderIds;
        private List<Settlement> settlements;
        private List<ReworkRecord> reworks;
        private long unreviewed;
        private BigDecimal clinicShare = BigDecimal.ZERO;
        private BigDecimal labShare = BigDecimal.ZERO;
    }
}
