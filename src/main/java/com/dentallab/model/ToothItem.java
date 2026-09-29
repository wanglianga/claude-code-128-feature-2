package com.dentallab.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 订单牙位明细：每颗牙/每个修复单元独立流转、独立返工。 */
@Getter
@Setter
@Entity
@Table(name = "tooth_item", indexes = @Index(name = "idx_item_order", columnList = "order_id"))
public class ToothItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private DentureOrder order;

    /** FDI 牙位，如 11、26、36；活动义齿可记录分区如 UL（上颌左）。 */
    @Column(nullable = false, length = 10)
    private String toothPosition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ToothItemStatus status = ToothItemStatus.PENDING;

    /** 当前所处工序；null 表示尚未开始。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private StageType currentStage;

    /** 设计摘要（扫描设计环节填写）。 */
    @Column(length = 500)
    private String designSummary;

    /** 当前设计版本号（首版 1，返工重设计递增）。 */
    @Column(nullable = false)
    private Integer designVersionNo = 0;

    /** 最近一次发货所属配送单。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id")
    private Shipment shipment;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
