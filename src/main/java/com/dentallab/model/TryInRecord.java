package com.dentallab.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 试戴记录：配送签收后自动生成安排，诊所回填试戴结果、医生备注与试戴照片。 */
@Getter
@Setter
@Entity
@Table(name = "try_in_record", indexes = @Index(name = "idx_tryin_order", columnList = "order_id"))
public class TryInRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private DentureOrder order;

    /** 关联牙位；整单试戴记录为空。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tooth_item_id")
    private ToothItem toothItem;

    /** 计划试戴时间（受配送签收结果影响）。 */
    private LocalDateTime scheduledAt;

    private LocalDateTime actualAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TryInResult result;

    /** 不密合的具体表现：咬合高 / 边缘不密合（result 非密合时必填）。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MisfitType issueType;

    /** 医生试戴备注（咬合高/边缘不密合的描述）。 */
    @Column(length = 1000)
    private String feedback;

    /** 试戴照片（多个以英文逗号分隔的地址/文件名）。 */
    @Column(length = 1000)
    private String photos;

    /** 患者体验评分 1-5。 */
    private Integer experienceScore;

    /** 记录试戴的医生。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id")
    private Account doctor;

    /** 不密合时自动创建并关联的返工单。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rework_id")
    private ReworkRecord rework;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
