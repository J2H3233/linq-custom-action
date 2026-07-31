package custompackageexample.core;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.JexlFeatures;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.introspection.JexlPermissions;

import com.automationanywhere.botcommand.exception.BotCommandException;

/**
 * JEXL 엔진 구성과 컴파일 캐시.
 *
 * <p>표현식은 Bot Agent 프로세스에서 평가된다. 문법({@link JexlFeatures})과 리플렉션
 * 범위({@link JexlPermissions}) 양쪽을 제한하지 않으면 임의 코드 실행 경로가 된다.
 *
 * <p>캐시는 static이다. SDK가 액션 실행마다 커맨드 클래스를 새로 인스턴스화하므로
 * 인스턴스 필드에 두면 재사용되지 않는다.
 */
public final class ExpressionEngine {

    /** 쿼리 표현식에서 원본 테이블에 바인딩되는 변수명. */
    public static final String QUERY_VAR = "table";

    /** 컴파일 캐시 상한. 초과분은 접근 순서 기준으로 제거된다. */
    private static final int MAX_CACHE_SIZE = 256;

    private static final JexlEngine ENGINE = buildEngine();

    private static final Map<String, JexlExpression> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, JexlExpression>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, JexlExpression> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            });

    /** 파싱 횟수. 캐시 동작 검증용. */
    private static final AtomicLong COMPILE_COUNT = new AtomicLong();

    private ExpressionEngine() {
    }

    private static JexlEngine buildEngine() {
        // methodCall은 유지한다. 끄면 네임스페이스 함수 호출도 함께 막힌다.
        // 클래스 접근 제한은 permissions가 담당한다.
        JexlFeatures features = new JexlFeatures()
                .sideEffect(false)        // 대입 금지. Dept = "IT" 는 파싱에서 거부된다
                .sideEffectGlobal(false)
                .newInstance(false)
                .loops(false)
                .lambda(true)             // r -> r.Dept == "IT". script(false)와 무관하게 동작한다
                .annotation(false)
                .script(false);           // 문장 나열과 블록 금지. 표현식 하나만 허용한다

        return new JexlBuilder()
                .features(features)
                .permissions(restrictedPermissions())
                .namespaces(Collections.singletonMap(null, (Object) new Fn()))
                .strict(true)             // 미정의 변수는 예외
                .safe(false)              // null 대상 접근을 null로 넘기지 않는다
                .silent(false)
                .cache(0)                 // 캐시는 CACHE가 관리한다
                .create();
    }

    /**
     * 리플렉션 범위 제한.
     *
     * <p>{@code RESTRICTED}는 java.io / java.net / java.lang.reflect 등을 차단하지만
     * {@code java.lang.Object}의 {@code getClass()}는 통과시킨다. getClass 하나로
     * 임의 클래스에 도달할 수 있으므로 별도로 차단한다.
     *
     * <p>{@code RESTRICTED}는 동시에 이 패키지의 메서드도 거부한다. 전역으로 완화하는
     * 대신 지정한 클래스만 예외로 둔다.
     *
     * <p>예외 목록은 표현식에서 도달 가능한 자바 API의 전부다.
     */
    private static JexlPermissions restrictedPermissions() {
        JexlPermissions base =
                JexlPermissions.RESTRICTED.compose("java.lang { Object { getClass(); } }");
        return new AllowOwn(base);
    }

    /**
     * 지정한 클래스만 {@code RESTRICTED}의 예외로 통과시킨다.
     *
     * <ul>
     *   <li>{@link Fn} — 화이트리스트 함수. Double / boolean 반환</li>
     *   <li>{@link Queryable} — 체인 메서드. 자신 또는 Table 반환</li>
     *   <li>{@link QueryRow} — 컬럼 접근. 셀 값 반환</li>
     * </ul>
     *
     * <p>세 클래스의 반환 타입은 원시값 또는 이 패키지의 타입뿐이므로 여기서 다른
     * 객체 그래프로 이동할 수 없다.
     */
    private static final class AllowOwn extends JexlPermissions.Delegate {

        private AllowOwn(JexlPermissions base) {
            super(base);
        }

        private static boolean owned(Class<?> clazz) {
            return clazz == Fn.class || clazz == Queryable.class || clazz == QueryRow.class;
        }

        @Override
        public boolean allow(Class<?> clazz) {
            return owned(clazz) || super.allow(clazz);
        }

        @Override
        public boolean allow(Method method) {
            return owned(method.getDeclaringClass()) || super.allow(method);
        }
    }

    /**
     * 표현식을 컴파일한다. 동일한 문자열은 캐시에서 반환한다.
     *
     * @throws BotCommandException 문법 오류. 행을 처리하기 전에 발생한다.
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

    /**
     * 체인 표현식을 평가한다. {@link #QUERY_VAR} 이름으로 {@link Queryable}이 바인딩된다.
     *
     * @throws BotCommandException 문법 오류, 평가 실패, 결과가 {@link Queryable}이 아닌 경우
     */
    public static Queryable evaluateQuery(String source, Queryable table) {
        JexlExpression compiled = compile(source);
        JexlContext context = new MapContext();
        context.set(QUERY_VAR, table);
        Object result;
        try {
            result = compiled.evaluate(context);
        } catch (RuntimeException e) {
            // Queryable이 던진 예외는 연산 순번과 행 번호를 이미 포함한다. JEXL이 이를
            // 감싸므로 catch 타입으로는 구분되지 않는다. 원인 사슬에서 꺼내 그대로 던진다.
            BotCommandException own = ownCause(e);
            if (own != null) {
                throw own;
            }
            throw new BotCommandException("쿼리 평가 실패 [" + source + "]: " + rootCause(e));
        }
        if (!(result instanceof Queryable)) {
            throw new BotCommandException("쿼리는 테이블을 반환해야 합니다. 실제: "
                    + (result == null ? "null" : result.getClass().getSimpleName())
                    + ". " + QUERY_VAR + " 로 시작하는 체인을 쓰십시오. 예: "
                    + QUERY_VAR + ".Where(r -> r.Dept == \"IT\")");
        }
        return (Queryable) result;
    }

    /** 파싱 횟수. 테스트 전용. */
    public static long compileCount() {
        return COMPILE_COUNT.get();
    }

    /** 캐시와 카운터 초기화. 테스트 전용. */
    public static void resetForTest() {
        CACHE.clear();
        COMPILE_COUNT.set(0);
    }

    /** 원인 사슬에서 {@link BotCommandException}을 찾는다. 없으면 null. */
    static BotCommandException ownCause(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof BotCommandException) {
                return (BotCommandException) cur;
            }
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return null;
    }

    /**
     * 원인 사슬 끝의 메시지. JEXL은 메시지에 파서 위치를 덧붙이므로 최종 원인만 남긴다.
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
