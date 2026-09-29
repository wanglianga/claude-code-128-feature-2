package com.dentallab.service;

import com.dentallab.config.BizException;
import com.dentallab.model.*;
import com.dentallab.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 结算：按订单难度、返工责任、加急费用与签收结果生成。
 * 应付 = 基础费×难度系数 + 加急费 + 诊所承担返工费 − 技工所承担返工费 − 配送扣罚。
 */
@Service
public class SettlementService {

    private static final BigDecimal LATE_PENALTY_RATE = new BigDecimal("0.05");
    private static final BigDecimal DAMAGED_PENALTY_RATE = new BigDecimal("0.10");

    private final SettlementRepo settlementRepo;
    private final DentureOrderRepo orderRepo;
    private final ToothItemRepo itemRepo;
    private final ReworkRecordRepo reworkRepo;
    private final ShipmentRepo shipmentRepo;
    private final PriceConfigRepo priceConfigRepo;
    private final AuthService authService;

    public SettlementService(SettlementRepo settlementRepo, DentureOrderRepo orderRepo, ToothItemRepo itemRepo,
                             ReworkRecordRepo reworkRepo, ShipmentRepo shipmentRepo,
                             PriceConfigRepo priceConfigRepo, AuthService authService) {
        this.settlementRepo = settlementRepo;
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.reworkRepo = reworkRepo;
        this.shipmentRepo = shipmentRepo;
        this.priceConfigRepo = priceConfigRepo;
        this.authService = authService;
    }

    @Transactional
    public Map<String, Object> generate(Account finance, Long orderId) {
        authService.requireRole(finance, Role.FINANCE, Role.LAB_ADMIN);
        DentureOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> BizException.notFound("订单#" + orderId));
        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new BizException("订单未全部试戴完成，不能结算，当前状态: " + order.getStatus().getLabel());
        }
        if (settlementRepo.findByOrderId(orderId).isPresent()) {
            throw new BizException("订单已生成结算单，不能重复生成");
        }
        List<ReworkRecord> reworks = reworkRepo.findByOrderIdOrderByIdAsc(orderId);
        long pending = reworks.stream().filter(r -> r.getResponsibility() == Responsibility.PENDING).count();
        if (pending > 0) {
            throw new BizException("存在 " + pending + " 条返工责任未判定（返工责任不清），请先判定责任再结算");
        }
        long causePending = reworks.stream()
                .filter(r -> r.getStatus() == ReworkStatus.CONFIRMED || r.getStatus() == ReworkStatus.COMPLETED)
                .filter(r -> r.getCause() == null).count();
        if (causePending > 0) {
            throw new BizException("存在 " + causePending + " 条返工尚未标记原因（设计问题/加工问题/医生取模问题），请先标记再结算");
        }

        int toothCount = itemRepo.findByOrderIdOrderByIdAsc(orderId).size();
        PriceConfig price = priceConfigRepo.findByProsthesisType(order.getProsthesisType())
                .orElseThrow(() -> new BizException("缺少价格配置: " + order.getProsthesisType()));

        BigDecimal base = price.getBasePrice().multiply(BigDecimal.valueOf(toothCount));
        BigDecimal multiplier = BigDecimal.ONE.add(
                new BigDecimal("0.15").multiply(BigDecimal.valueOf(order.getDifficulty() - 1L)));
        BigDecimal difficultyAmount = base.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
        BigDecimal rushFee = order.isRush()
                ? difficultyAmount.multiply(price.getRushRate()).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal clinicShare = reworks.stream().map(ReworkRecord::getClinicShare)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal labShare = reworks.stream().map(ReworkRecord::getLabShare)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal deliveryPenalty = BigDecimal.ZERO;
        StringBuilder penaltyNote = new StringBuilder();
        for (Shipment shipment : shipmentRepo.findByOrderIdOrderByIdDesc(orderId)) {
            if (shipment.getSignResult() == SignResult.LATE) {
                deliveryPenalty = deliveryPenalty.add(
                        difficultyAmount.multiply(LATE_PENALTY_RATE).setScale(2, RoundingMode.HALF_UP));
                penaltyNote.append("配送延误扣罚5%; ");
            } else if (shipment.getSignResult() == SignResult.DAMAGED) {
                deliveryPenalty = deliveryPenalty.add(
                        difficultyAmount.multiply(DAMAGED_PENALTY_RATE).setScale(2, RoundingMode.HALF_UP));
                penaltyNote.append("配送破损扣罚10%; ");
            }
        }

        BigDecimal total = difficultyAmount.add(rushFee).add(clinicShare)
                .subtract(labShare).subtract(deliveryPenalty);
        if (total.signum() < 0) {
            total = BigDecimal.ZERO;
        }

        Settlement settlement = new Settlement();
        settlement.setOrder(order);
        settlement.setBaseAmount(base);
        settlement.setDifficultyMultiplier(multiplier);
        settlement.setRushFee(rushFee);
        settlement.setReworkClinicShare(clinicShare);
        settlement.setReworkLabDeduction(labShare);
        settlement.setDeliveryPenalty(deliveryPenalty);
        settlement.setTotalAmount(total);
        settlement.setDetail(String.format(
                "基础费=%s×%d牙位=%s; 难度系数=%s(难度%d); 加急费=%s; 诊所承担返工=%s; 技工所承担返工(扣减)=%s; 配送扣罚=%s%s; 应付=%s",
                price.getBasePrice(), toothCount, base, multiplier, order.getDifficulty(),
                rushFee, clinicShare, labShare, deliveryPenalty,
                penaltyNote.length() == 0 ? "" : "(" + penaltyNote + ")", total)
                + reworkCauseBreakdown(reworks));
        settlementRepo.save(settlement);
        return Views.settlement(settlement);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getByOrder(Long orderId) {
        Settlement settlement = settlementRepo.findByOrderId(orderId)
                .orElseThrow(() -> BizException.notFound("订单#" + orderId + "的结算单"));
        return Views.settlement(settlement);
    }

    /** 返工责任调整明细：逐条列出原因分类→责任→金额。 */
    private String reworkCauseBreakdown(List<ReworkRecord> reworks) {
        String breakdown = reworks.stream()
                .filter(r -> r.getCause() != null)
                .map(r -> "返工#" + r.getId() + "[" + r.getToothItem().getToothPosition() + "]"
                        + r.getCause().getLabel() + "→" + r.getResponsibility().getLabel()
                        + "(诊所" + r.getClinicShare() + "/技工所" + r.getLabShare() + ")")
                .reduce((a, b) -> a + "; " + b).orElse("");
        return breakdown.isEmpty() ? "" : "; 返工责任调整: " + breakdown;
    }
}
