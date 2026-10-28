package com.semojum.backend.domain.user;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.user.dto.OutputOptions;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/** 출력용 설정이 users.output_options(jsonb)에 실제로 저장되고 다시 읽히는지 — 실제 PostgreSQL(Testcontainers) */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Testcontainers
class OutputOptionsPersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Autowired UserRepository userRepository;
    @Autowired EntityManager em;

    @Test
    void jsonb_칸에_저장하고_다시_읽는다() {
        User user = userRepository.save(User.builder().loginId("u-output").password("x").build());
        OutputOptions sent = new OutputOptions("11x11.5", 25, 30, 3, "every", false, "word");
        user.updateOutputOptions(sent);
        userRepository.saveAndFlush(user);
        em.clear();

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertEquals(sent, reloaded.resolveOutputOptions());
    }

    @Test
    void 저장한_적_없으면_칸은_비고_공장_기본값을_준다() {
        User user = userRepository.saveAndFlush(User.builder().loginId("u-output-none").password("x").build());
        em.clear();

        assertEquals(OutputOptions.defaults(), userRepository.findById(user.getId()).orElseThrow().resolveOutputOptions());
    }
}
