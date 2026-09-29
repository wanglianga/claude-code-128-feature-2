package com.dentallab.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 诊所月结单。
 * 流程：财务生成 → 技工所同步确认 → 同步诊所 → 诊所确认（账期内返工须全部复核）→ 财务最终确认。
 * 存在未解决月结争议时不能进入财务确认。
 */
@Getter
@Setter
@Entity
@Table(name = "monthly_statement",
        uniqueConstraints = @UniqueConstraint(name = "uk_statement_clinic_month", columnNames = {"clinic_id", "year_month"}))
public class MonthlyStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clinic_id")
    private Clinic clinic;

    /** 账期，格式 yyyy-MM。 */
    @Column(nullable = false, length = 7)
    private String yearMonth;

    @Column(nullable = false)
    private Integer orderCount = 0;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /** 生成时账期内未复核返工数（>0 时不允许诊所确认）。 */
    @Column(nullable = false)
    private Integer unreviewedReworkCount = 0;

    /** 账期内返工责任调整：诊所承担合计（向诊所加收）。 */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal reworkClinicShare = BigDecimal.ZERO;

    /** 账期内返工责任调整：技工所承担合计（从诊所应付扣减）。 */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal reworkLabDeduction = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatementStatus status = StatementStatus.GENERATED;

    @Column(nullable = false)
    private LocalDateTime generatedAt = LocalDateTime.now();

    /** 技工所同步时间。 */
    private LocalDateTime labSyncedAt;

    /** 诊所同步时间（技工所同步时一并记录，单据对诊所可见）。 */
    private LocalDateTime clinicSyncedAt;

    private LocalDateTime confirmedAt;

    /** 财务最终确认时间。 */
    private LocalDateTime financeConfirmedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "finance_confirmed_by")
    private Account financeConfirmedBy;
}
