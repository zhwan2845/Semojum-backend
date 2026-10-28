package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.dto.LayoutOptions;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 점역된 꼬리말({@code jobs.footer_braille})을 다룬다.
 *
 * <p>서버는 꼬리말을 점역하지 않는다 — 앱이 {@code footerText}로 점역한다. 새 작업의 {@code footer_braille}은 늘 null
 *
 * <p>값이 있는 건 서버가 점역하던 예전 작업뿐이다. 이 클래스는 그 값을 돌려주고, 판면이 바뀔 때 들어가는지 검사한다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FooterBrailleService {

    /** 항목 사이 두 칸 이상 (지침 1장3-1) — 쪽 번호와 꼬리말 사이 간격 */
    private static final int GAP = 2;


    /**
     * 쪽 조회 · 설정 조회가 쓸 점역 꼬리말 — 저장된 값을 그대로 준다. 없으면 null(새 작업은 늘 null).
     */
    public String resolve(Job job) {
        return job.getFooterBraille();
    }

    /**
     * 점역된 꼬리말이 페이지행에 들어가는지 검사한다 — 안 들어가면 COMMON4000.
     *
     * <ul>
     *   <li>쓰는 곳: 예전 작업을 저장하면서 판면을 좁힐 때(32칸→20칸 등) — 새 작업은 점역 값이 없어 부르지 않는다</li>
     *   <li>⚠️ 조판 라이브러리는 자리에 안 맞는 꼬리말을 <b>말없이 뒤에서 자른다</b>(지침 1장3-4) — 그래서 여기서 막는다</li>
     *   <li>남는 자리는 면마다 조금씩 다르다(쪽 번호 자릿수). <b>가장 빠듯한 경우</b>로 잡는다 — 원본 쪽 · 점자 면 번호가 모두 가장 긴 자릿수일 때</li>
     * </ul>
     */
    void validateFits(String braille, LayoutOptions options, int totalPages) {
        LayoutOptions opts = options == null ? LayoutOptions.legacy(false) : options.withDefaults();
        if ("none".equals(opts.pageNumberLine())) return;   // 페이지행 자체가 없으면 잘릴 일이 없다

        int available = opts.cellsPerLine()
                - (opts.showSourcePageNumber() ? numCells(opts.sourcePageStart() + totalPages - 1) + GAP : 0)
                - (opts.showBraillePageNumber() ? numCells(opts.braillePageStart() + totalPages - 1) + GAP : 0);

        if (braille.length() > available) {
            log.warn("꼬리말이 판면에 안 들어감: 점역 {}칸 > 남는 자리 {}칸 (한 줄 {}칸)",
                    braille.length(), available, opts.cellsPerLine());
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
    }

    /** 수표(⠼) 1칸 + 자릿수. 예: 102 → 4칸 */
    private int numCells(int page) {
        return 1 + String.valueOf(Math.max(page, 1)).length();
    }
}
