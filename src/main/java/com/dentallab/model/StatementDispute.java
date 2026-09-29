package com.dentallab.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 月结争议：诊所/技工所对月结金额或返工责任调整有异议时提出，处理完并财务确认前挂起。 */
@Getter
@Setter
@Entity
@Table(name = "statement_dispute",
        indexes = @Index(name = "idx_dispute_statement", columnList = "statement_id"))
public class StatementDispute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "statement_id")
    private MonthlyStatement statement;

    /** 提出方角色：CLINIC-诊所 / LAB-技工所。 */
    @Column(nullable = false, length = 10)
    private String raisedSide;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "raised_by")
    private Account raisedBy;

    @Column(nullable = false, length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DisputeStatus status = DisputeStatus.OPEN;

    @Column(length = 1000)
    private String resolution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private Account resolvedBy;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime resolvedAt;
}
