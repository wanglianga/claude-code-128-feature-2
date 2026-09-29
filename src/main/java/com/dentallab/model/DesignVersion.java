package com.dentallab.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 设计版本：牙位每次完成扫描设计（含返工重做设计）生成一个新版本，
 * 试戴返工时关联的就是患者试戴那版设计，便于判断是否为设计问题。
 */
@Getter
@Setter
@Entity
@Table(name = "design_version",
        uniqueConstraints = @UniqueConstraint(name = "uk_design_version",
                columnNames = {"tooth_item_id", "version"}),
        indexes = @Index(name = "idx_design_version_item", columnList = "tooth_item_id"))
public class DesignVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tooth_item_id")
    private ToothItem toothItem;

    /** 版本号，同一牙位从 1 递增，返工重做设计会产生新版本。 */
    @Column(nullable = false)
    private Integer version;

    @Column(length = 500)
    private String designSummary;

    /** 设计者（扫描设计工序操作人）。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "designer_id")
    private Account designer;

    /** 设计照片（英文逗号分隔）。 */
    @Column(length = 1000)
    private String photos;

    @Column(length = 500)
    private String note;

    /** 触发该版本的返工单（首版为空）。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rework_id")
    private ReworkRecord rework;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
