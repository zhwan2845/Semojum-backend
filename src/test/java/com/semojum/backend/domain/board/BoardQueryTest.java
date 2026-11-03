package com.semojum.backend.domain.board;

import com.semojum.backend.domain.board.entity.BoardAnswer;
import com.semojum.backend.domain.board.entity.BoardPost;
import com.semojum.backend.domain.board.repository.BoardAnswerRepository;
import com.semojum.backend.domain.board.repository.BoardPostRepository;
import com.semojum.backend.global.grpc.BrailleGrpcClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 게시판 목록 조건(대기만 · 답변 받은 글)과 답변 수 · 처리 상태 쿼리를 실제 PostgreSQL로 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "JWT_SECRET=test-secret-key-for-context-load-only-32bytes+",
        "DB_PASSWORD=test",
        "GRPC_CERT_PATH=classpath:grpc/test-server.crt"
})
class BoardQueryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @MockitoBean
    BrailleGrpcClient brailleGrpcClient;

    @Autowired
    BoardPostRepository postRepository;

    @Autowired
    BoardAnswerRepository answerRepository;

    @Test
    void 대기와_답변_받은_글은_운영자_답변과_이어_묻기로_가른다() {
        UUID writer = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        Long none = post(writer, "답변 없음");
        Long answered = post(writer, "답변 받음");
        answer(answered, admin, false);
        Long reopened = post(writer, "답변 뒤 이어 물음");
        answer(reopened, admin, false);
        answer(reopened, writer, true);
        Long onlyFollowUp = post(writer, "이어 묻기만");
        answer(onlyFollowUp, writer, true);

        assertEquals(List.of(onlyFollowUp, reopened, none), ids(true, false));
        assertEquals(List.of(reopened, answered), ids(false, true));

        Map<Long, Long> counts = new HashMap<>();
        for (Object[] r : answerRepository.countByPostIds(List.of(answered, reopened, onlyFollowUp))) {
            counts.put((Long) r[0], (Long) r[1]);
        }
        assertEquals(Map.of(answered, 1L, reopened, 1L), counts);   // 이어 묻기는 답변 수에 안 든다

        Map<Long, Boolean> last = new HashMap<>();
        for (Object[] r : answerRepository.findLastFromAuthorByPostIds(List.of(answered, reopened))) {
            last.put((Long) r[0], (Boolean) r[1]);
        }
        assertEquals(Map.of(answered, false, reopened, true), last);
    }

    private List<Long> ids(boolean waitingOnly, boolean answeredOnly) {
        return postRepository.search(UUID.randomUUID(), true, false, waitingOnly, answeredOnly, false,
                        "TITLE_CONTENT", "%", PageRequest.of(0, 10))
                .getContent().stream().map(BoardPost::getId).toList();
    }

    private Long post(UUID writer, String title) {
        return postRepository.save(BoardPost.builder().userId(writer).title(title).content("본문")
                .category("RESULT").secret(false).build()).getId();
    }

    private void answer(Long postId, UUID userId, boolean fromAuthor) {
        answerRepository.save(BoardAnswer.builder().postId(postId).userId(userId).content("글").fromAuthor(fromAuthor).build());
    }
}
