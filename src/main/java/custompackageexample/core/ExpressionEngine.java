package custompackageexample.core;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.JexlFeatures;
import org.apache.commons.jexl3.introspection.JexlPermissions;

import com.automationanywhere.botcommand.exception.BotCommandException;

/**
 * JEXL 엔진 설정과 컴파일 캐시.
 *
 * <p><b>보안이 핵심이다.</b> 봇 개발자는 텍스트 필드에 무엇이든 칠 수 있고 이 코드는
 * Bot Agent 프로세스 안에서 돈다. 문법 자체를 제한하지 않으면 표현식 필드가
 * 임의 코드 실행 창구가 된다.
 *
 * <p>캐시가 static인 이유: SDK는 액션 실행마다 클래스를 새로 인스턴스화한다.
 * 인스턴스 필드에 담으면 봇의 Loop 안에서 매번 다시 컴파일된다.
 */
public final class ExpressionEngine {

    /** 캐시 상한. 무한 증식 방지용. 표현식 종류가 이보다 많은 봇은 현실적으로 없다. */
    private static final int MAX_CACHE_SIZE = 256;

    private static final JexlEngine ENGINE = buildEngine();

    private static final Map<String, JexlExpression> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, JexlExpression>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, JexlExpression> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            });

    /** 실제 파싱 횟수. 행이 몇 개든 파싱이 1회인지 테스트로 확인하기 위한 카운터. */
    private static final AtomicLong COMPILE_COUNT = new AtomicLong();

    private ExpressionEngine() {
    }

    private static JexlEngine buildEngine() {
        // methodCall은 켜 둔다. 껐을 때 네임스페이스 함수 호출까지 막히고,
        // 클래스 접근 차단은 아래 permissions가 담당하므로 여기서 끌 이유가 없다.
        JexlFeatures features = new JexlFeatures()
                .sideEffect(false)        // 대입 금지. Dept = "IT" 를 파싱 단계에서 잡는다.
                .sideEffectGlobal(false)
                .newInstance(false)       // new 금지
                .loops(false)             // 무한루프 금지
                .lambda(false)
                .annotation(false)
                .script(false);           // 여러 문장/블록 금지. 표현식 하나만 허용.

        return new JexlBuilder()
                .features(features)
                .permissions(restrictedPermissions())
                .namespaces(Collections.singletonMap(null, (Object) new Fn()))
                .strict(true)             // 컬럼명 오타를 예외로 만든다
                .safe(false)              // null.foo()가 조용히 null이 되지 않게 한다
                .silent(false)
                .cache(0)                 // JEXL 내부 캐시는 끈다. 캐시는 우리가 관리한다.
                .create();
    }

    /**
     * 클래스 리플렉션 차단.
     *
     * <p>{@code RESTRICTED}는 java.io / java.net / java.lang.reflect 등을 막지만
     * {@code ''.getClass()}는 {@code java.lang.Object}의 메서드라서 통과한다(실측 확인).
     * getClass 하나만 열려 있어도 거기서부터 전부 뚫리므로 별도로 막는다.
     *
     * <p>반대 방향의 문제도 있다. {@code RESTRICTED}는 우리 패키지의 메서드까지 거부해서
     * {@link Fn}의 화이트리스트 함수가 {@code unsolvable function}이 된다(실측 확인).
     * RESTRICTED를 전역적으로 느슨하게 풀지 않고, 우리가 소유하고 감사하는 {@code Fn}
     * 한 클래스만 예외로 둔다. Fn의 메서드는 Double/boolean만 반환하므로
     * 여기서 다른 객체로 건너갈 수 있는 경로가 없다.
     */
    private static JexlPermissions restrictedPermissions() {
        JexlPermissions base =
                JexlPermissions.RESTRICTED.compose("java.lang { Object { getClass(); } }");
        return new AllowFn(base);
    }

    /** {@link Fn}만 RESTRICTED의 예외로 통과시킨다. */
    private static final class AllowFn extends JexlPermissions.Delegate {

        private AllowFn(JexlPermissions base) {
            super(base);
        }

        @Override
        public boolean allow(Class<?> clazz) {
            return clazz == Fn.class || super.allow(clazz);
        }

        @Override
        public boolean allow(Method method) {
            return method.getDeclaringClass() == Fn.class || super.allow(method);
        }
    }

    /**
     * 표현식을 컴파일한다. 같은 문자열이면 캐시에서 돌려주므로 파싱은 1회다.
     *
     * @throws BotCommandException 문법 오류. 첫 행을 건드리기 전에 잡히므로
     *                             봇 개발자는 "3200행에서 터짐"이 아니라 즉시 오타를 본다.
     */
    public static JexlExpression compile(String source) {
        JexlExpression cached = CACHE.get(source);
        if (cached != null) {
            return cached;
        }
        JexlExpression compiled;
        try {
            compiled = ENGINE.createExpression(source);
            COMPILE_COUNT.incrementAndGet();
        } catch (JexlException e) {
            throw new BotCommandException(
                    "표현식 문법 오류 [" + source + "]: " + rootCause(e));
        }
        CACHE.put(source, compiled);
        return compiled;
    }

    /** 실제 파싱이 몇 번 일어났는지. 테스트 전용. */
    public static long compileCount() {
        return COMPILE_COUNT.get();
    }

    /** 테스트 전용. 캐시와 카운터를 비운다. */
    public static void resetForTest() {
        CACHE.clear();
        COMPILE_COUNT.set(0);
    }

    /**
     * 봇 개발자에게 보여줄 한 줄 원인. JEXL은 메시지에 파일명/위치를 길게 붙이는데
     * 그게 Control Room 로그에서는 노이즈다.
     */
    static String rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String message = cur.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return cur.getClass().getSimpleName();
        }
        return message.trim();
    }
}
