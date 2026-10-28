package com.semojum.backend.domain.admin;

import com.semojum.backend.domain.admin.service.AdminAccessGuard;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 모든 운영자 API 핸들러가 <b>무엇보다 먼저</b> {@link AdminAccessGuard#check()}를 부르는지.
 *
 * <p>검증이 컨트롤러 메서드마다 직접 부르는 방식이라 하나라도 빠지면 권한 구멍이 생긴다.
 * {@code domain.admin.controller} 패키지의 컨트롤러를 모두 찾아, 검증이 거절하도록 만든 뒤 핸들러를 하나씩 부른다 —
 * 서비스가 하나라도 불리면(검증보다 먼저 일을 하면) 실패한다.
 */
class AdminAccessGuardCoverageTest {

    static class Denied extends RuntimeException {}

    @Test
    void 운영자_핸들러는_모두_검증부터_부른다() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var candidates = scanner.findCandidateComponents("com.semojum.backend.domain.admin.controller");
        assertFalse(candidates.isEmpty());

        List<String> checked = new ArrayList<>();
        for (var bd : candidates) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            Constructor<?> ctor = type.getConstructors()[0];
            AdminAccessGuard guard = mock(AdminAccessGuard.class);
            doThrow(new Denied()).when(guard).check();
            List<Object> deps = new ArrayList<>();
            for (Class<?> p : ctor.getParameterTypes()) deps.add(p == AdminAccessGuard.class ? guard : mock(p));
            Object controller = ctor.newInstance(deps.toArray());

            for (Method m : type.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class)) continue;
                Object[] args = new Object[m.getParameterCount()];
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) args[i] = defaultValue(ps[i]);

                InvocationTargetException e = assertThrows(InvocationTargetException.class,
                        () -> m.invoke(controller, args), type.getSimpleName() + "#" + m.getName());
                assertInstanceOf(Denied.class, e.getCause(),
                        type.getSimpleName() + "#" + m.getName() + " 이 검증보다 먼저 다른 일을 했다");
                for (Object dep : deps) if (dep != guard) verifyNoInteractions(dep);
                checked.add(type.getSimpleName() + "#" + m.getName());
            }
        }
        // 운영자 API 42개 — 핸들러를 못 찾아 아무것도 검사하지 않고 통과하는 일을 막는다
        assertEquals(42, checked.size(), checked.toString());
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        return type == int.class ? 0 : (short) 0;
    }
}
