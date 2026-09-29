package com.dentallab.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 返工记录：按牙位追踪责任归属与费用分摊。
 * 试戴不密合返工自动关联：试戴那版设计、医生备注、技工工序操作、试戴照片；
 * 诊所确认返工时限与是否收费；技工所在确认后标记设计/加工/取模原因，月结按责任调整费用。
 */
@Getter
@Setter
@Entity
@Table(name = "rework_record", indexes = {
        @Index(name = "idx_rework_order", columnList = "order_id"),
        @Index(name = "idx_rework_item", columnList = "tooth_item_id"),
        @Index(name = "idx_rework_status", columnList = "status")
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

    /** 返工来源：试戴不密合 / 配送破损 / 人工登记。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReworkSource source = ReworkSource.MANUAL;

    /** 咬合高 / 边缘不密合（试戴类返工时记录）。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MisfitType misfitType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Responsibility responsibility = Responsibility.PENDING;

    /** 技工所在诊所确认返工后标记的原因三分类。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ReworkCause cause;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReworkStatus status = ReworkStatus.AWAIT_CONFIRM;

    /** 返工成本（判定责任时核定）。 */
    @Column(precision = 12, scale = 2)
    private BigDecimal reworkCost = BigDecimal.ZERO;

    /** 诊所承担金额。 */
    @Column(precision = 12, scale = 2)
    private BigDecimal clinicShare = BigDecimal.ZERO;

    /** 技工所承担金额（结算时从应付中扣减）。 */
    @Column(precision = 12, scale = 2)
    private BigDecimal labShare = BigDecimal.ZERO;

    // ---- 试戴返工证据链：设计版本、医生备注、技工操作、试戴照片 ----

    /** 患者试戴时对应的设计版本（判断设计问题的依据）。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "design_version_id")
    private DesignVersion designVersion;

    /** 触发返工的试戴记录（含医生备注与试戴照片）。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "try_in_id")
    private TryInRecord tryIn;

    /** 技工操作摘要：返工前该牙位各工序操作人/时间快照（文本留痕）。 */
    @Column(length = 2000)
    private String technicianOps;

    // ---- 诊所确认：返工时限、责任判断、是否收费 ----

    /** 诊所有无异议（确认/驳回）。 */
    @Column(nullable = false)
    private boolean clinicAccepted = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_by")
    private Account confirmedBy;

    private LocalDateTime confirmedAt;

    /** 诊所要求的返工完成时限（小时）。 */
    private Integer deadlineHours;

    /** 据确认时限计算的返工截止时间。 */
    private LocalDateTime deadlineAt;

    /** 诊所确认本次返工是否收费（免费返修 / 收费重做）。 */
    private Boolean chargeable;

    /** 诊所在确认时的备注（责任初步判断、患者沟通情况）。 */
    @Column(length = 1000)
    private String confirmNote;

    /** 诊所驳回原因。 */
    @Column(length = 1000)
    private String rejectReason;

    /** 诊所对账前复核标记。 */
    @Column(nullable = false)
    private boolean clinicReviewed = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private Account reviewedBy;

    private LocalDateTime reviewedAt;

    // ---- 技工所原因标记 ----

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cause_marked_by")
    private Account causeMarkedBy;

    private LocalDateTime causeMarkedAt;

    @Column(length = 1000)
    private String causeNote;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime completedAt;
}
