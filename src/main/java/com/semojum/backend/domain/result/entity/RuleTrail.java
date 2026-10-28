package com.semojum.backend.domain.result.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "rule_trails")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RuleTrail {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    // 다형성 FK: text_elements.id 또는 braille_elements.id
    @Column(nullable = false, columnDefinition = "uuid")
    private UUID elementId;

    // "TEXT" or "BRAILLE"
    @Column(nullable = false)
    private String elementType;

    @Column(nullable = false)
    private String ruleId;

    private String source;
    private String priority;

    @Column(nullable = false)
    private String section;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String excerpt;

    private Integer lineNo;
    private Integer colStart;
    private Integer colEnd;
    private String tag;

    // 규정 출처 상세 — 어느 기관의 몇 년 판인지
    @Column(length = 100)
    private String publisher;

    private Integer version;

    // 조문 경로(부·장·절·항·호·목). 단계 수가 규정마다 달라 jsonb 한 칸으로 담는다
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "section_path", columnDefinition = "jsonb")
    private Map<String, String> sectionPath;

    @Builder
    public RuleTrail(UUID elementId, String elementType, String ruleId,
                     String source, String priority,
                     String section, String title, String excerpt,
                     Integer lineNo, Integer colStart, Integer colEnd, String tag,
                     String publisher, Integer version, Map<String, String> sectionPath) {
        this.elementId = elementId;
        this.elementType = elementType;
        this.ruleId = ruleId;
        this.source = source;
        this.priority = priority;
        this.section = section;
        this.title = title;
        this.excerpt = excerpt;
        this.lineNo = lineNo;
        this.colStart = colStart;
        this.colEnd = colEnd;
        this.tag = tag;
        this.publisher = publisher;
        this.version = version;
        this.sectionPath = sectionPath;
    }
}
