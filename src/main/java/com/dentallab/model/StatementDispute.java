package com.dentallab.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 月结争议：诊所对月结单提出异议，技工所/财务处理，全程留痕同步。 */
@Getter
@Setter
@Entity
@Table(name = "statement_dispute", indexes = @Index(name = "idx_dispute_statement", columnList = "statement_id"))
public class StatementDispute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "statement_id")
    private MonthlyStatement statement;

    @Column(nullable = false, length = 1000)
    private String reason;

    /** 关联返工单（可选）：争议通常围绕某条返工责任/费用调整。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rework_id")
    private ReworkRecord rework;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "raised_by")
    private Account raisedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatementDisputeStatus status = StatementDisputeStatus.OPEN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private Account resolvedBy;

    @Column(length = 1000)
    private String resolution;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime resolvedAt;
}
