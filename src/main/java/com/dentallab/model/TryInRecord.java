package com.dentallab.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 试戴记录：配送签收后自动生成安排，诊所回填试戴结果、咬合/边缘问题、医生备注与试戴照片。 */
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

    /** 不密合时的具体问题：咬合高 / 边缘不密合。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MisfitType misfitType;

    /** 医生试戴备注（咬合高点位置、边缘缝隙、食物嵌塞等）。 */
    @Column(length = 1000)
    private String doctorNotes;

    /** 患者试戴体验反馈。 */
    @Column(length = 1000)
    private String feedback;

    /** 患者体验评分 1-5。 */
    private Integer experienceScore;

    /** 试戴照片（多个以英文逗号分隔的地址/文件名），返工的关键证据。 */
    @Column(length = 1000)
    private String photos;

    /** 记录试戴结果的医生。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by")
    private Account recordedBy;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
