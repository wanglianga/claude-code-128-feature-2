package com.dentallab.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 返工记录（试戴不密合返工）：
 * 医生试戴反馈咬合高/边缘不密合并附照片 → 自动建返工单，关联设计版本、医生备注、试戴记录；
 * 技工所提交返工方案（返工时限、责任、是否收费）→ 诊所确认/驳回；
 * 确认后技工重做并标记原因（设计/加工/医生取模），月结按责任调整费用；
 * 诊所对账前逐条复核。
 */
@Getter
@Setter
@Entity
@Table(name = "rework_record", indexes = {
        @Index(name = "idx_rework_order", columnList = "order_id"),
        @Index(name = "idx_rework_item", columnList = "tooth_item_id")
})
public class ReworkRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private DentureOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tooth_item_id")
    private ToothItem toothItem;

    @Column(nullable = false, length = 1000)
    private String reason;

    /** 不密合表现：咬合高 / 边缘不密合 / 两者都有。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MisfitType issueType;

    /** 试戴照片（来自试戴记录，多个以英文逗号分隔）。 */
    @Column(length = 1000)
    private String tryInPhotos;

    /** 医生试戴备注。 */
    @Column(length = 1000)
    private String doctorNotes;

    /** 关联触发返工的试戴记录。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "try_in_id")
    private TryInRecord tryIn;

    /** 关联导致问题的设计版本（返工关联设计版本）。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "design_version_id")
    private DesignVersion designVersion;

    // ---- 技工所返工方案（提交后待诊所确认） ----

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReworkStatus status = ReworkStatus.AWAITING_PLAN;

    /** 返工时限（小时），技工所方案承诺。 */
    private Integer reworkDeadlineHours;

    /** 方案建议责任归属，诊所确认后生效。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Responsibility proposedResponsibility;

    /** 最终责任归属（诊所确认方案时落定；兼容判定接口）。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Responsibility responsibility;

    /** 返工是否向诊所收费（技工所方案提出，诊所确认）。 */
    private Boolean chargeClinic;

    /** 技工操作说明（重做方式/技工处理备注）。 */
    @Column(length = 1000)
    private String labAction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "planned_by")
    private Account plannedBy;

    private LocalDateTime planSubmittedAt;

    /** 诊所驳回原因。 */
    @Column(length = 1000)
    private String rejectReason;

    // ---- 诊所确认 ----

    private boolean clinicConfirmed = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_by")
    private Account confirmedBy;

    private LocalDateTime confirmedAt;

    // ---- 技工所原因标记（返工确认后、重做质检时标记） ----

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ReworkCause cause;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marked_by")
    private Account markedBy;

    private LocalDateTime causeMarkedAt;

    // ---- 责任成本与分摊（月结按责任调整费用） ----

    /** 返工成本（方案/判定责任时核定）。 */
    @Column(precision = 12, scale = 2)
    private BigDecimal reworkCost = BigDecimal.ZERO;

    /** 诊所承担金额（向诊所收取）。 */
    @Column(precision = 12, scale = 2)
    private BigDecimal clinicShare = BigDecimal.ZERO;

    /** 技工所承担金额（月结时从应付中扣减）。 */
    @Column(precision = 12, scale = 2)
    private BigDecimal labShare = BigDecimal.ZERO;

    /** 诊所对账前复核标记。 */
    @Column(nullable = false)
    private boolean clinicReviewed = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private Account reviewedBy;

    private LocalDateTime reviewedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime completedAt;
}
