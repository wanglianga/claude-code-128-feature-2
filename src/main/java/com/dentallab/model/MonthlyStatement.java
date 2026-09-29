package com.dentallab.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 诊所月结单。
 * 流程：财务生成 → 技工所同步诊所 → 诊所确认 → 财务确认；
 * 期间诊所可发起争议（月结争议同步记录），解决后重新同步确认。
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

    /** 账期内返工诊所承担金额合计（向诊所加收）。 */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal reworkClinicShare = BigDecimal.ZERO;

    /** 账期内返工技工所承担金额合计（从应付扣减）。 */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal reworkLabDeduction = BigDecimal.ZERO;

    /** 月结争议数（同步记录，含已解决）。 */
    @Column(nullable = false)
    private Integer disputeCount = 0;

    /** 生成时账期内未复核返工数（>0 时诊所不能确认）。 */
    @Column(nullable = false)
    private Integer unreviewedReworkCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatementStatus status = StatementStatus.GENERATED;

    @Column(nullable = false)
    private LocalDateTime generatedAt = LocalDateTime.now();

    /** 技工所同步给诊所的时间。 */
    private LocalDateTime syncedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "synced_by")
    private Account syncedBy;

    private LocalDateTime confirmedAt;

    /** 财务端确认时间。 */
    private LocalDateTime financeConfirmedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "finance_confirmed_by")
    private Account financeConfirmedBy;
}
