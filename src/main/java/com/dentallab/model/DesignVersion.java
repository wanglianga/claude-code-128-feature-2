package com.dentallab.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 设计版本：牙位每次扫描设计（含返工重设计）产生一个版本。
 * 返工单关联到导致问题的设计版本，实现“返工关联设计版本”。
 */
@Getter
@Setter
@Entity
@Table(name = "design_version", indexes = {
        @Index(name = "idx_design_version_item", columnList = "tooth_item_id"),
        @Index(name = "idx_design_version_order", columnList = "order_id")
})
public class DesignVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private DentureOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tooth_item_id")
    private ToothItem toothItem;

    /** 版本号，从 1 开始；返工重设计时递增。 */
    @Column(nullable = false)
    private Integer versionNo;

    /** 设计文件（多个以英文逗号分隔的地址/文件名）。 */
    @Column(length = 1000)
    private String designFiles;

    /** 设计摘要。 */
    @Column(length = 500)
    private String summary;

    /** 设计技工。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "designed_by")
    private Account designedBy;

    /** 返工重设计时关联的返工单；首版为空。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rework_id")
    private ReworkRecord rework;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
