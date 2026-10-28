package com.semojum.backend.domain.user.service;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.user.dto.OutputOptions;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 출력용 점자문서 설정의 계정 기본값 — 기본 점역 옵션({@link BrailleOptionsService})과 같은 방식이다:
 * 저장한 적 없으면 공장 기본값을 주고, 저장은 창 전체를 통째로 덮어쓴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutputOptionsService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public OutputOptions get(String userId) {
        return findUser(userId).resolveOutputOptions();
    }

    /**
     * 창의 「설정 저장(기본값으로 저장)」 — 창이 항목 전부를 보여주므로 통째로 덮어쓴다.
     * 빠진 항목은 기본값으로 채우고(「안 보낸 항목은 그대로」가 아니다), 범위 밖 값은 COMMON4000
     */
    @Transactional
    public OutputOptions update(String userId, OutputOptions requested) {
        User user = findUser(userId);
        OutputOptions options = requested == null ? OutputOptions.defaults() : requested.withDefaults();
        if (!options.isValid()) throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);

        user.updateOutputOptions(options);
        log.info("출력용 점자문서 설정 저장: user={}, 용지={}", user.getLoginId(), options.paperSize());
        return options;
    }

    private User findUser(String userId) {
        return userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }
}
