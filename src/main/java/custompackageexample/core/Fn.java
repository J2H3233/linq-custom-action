package custompackageexample.core;

import com.automationanywhere.botcommand.exception.BotCommandException;

/**
 * 표현식에서 호출 가능한 함수 화이트리스트.
 *
 * <p>JEXL 네임스페이스의 {@code null} 키에 등록되므로 접두사 없이 호출된다
 * ({@code toNumber(Salary) > 5000}). 이 클래스에 없는 메서드는 호출할 수 없다.
 *
 * <p>파라미터 타입이 모두 {@code Object}인 것은 컬럼 값이 {@link Coercion}을 거쳐
 * String / Double / Boolean 중 하나로 들어오기 때문이다.
 */
public class Fn {

    /**
     * 액션 UI에 표시할 함수 목록. 어노테이션 값이라 컴파일 상수여야 한다.
     * 실제 메서드 목록과의 일치는 {@code FnTest}가 검증한다.
     */
    public static final String FUNCTION_HELP = "toNumber() · toNumberOr() · isEmpty() · "
            + "eqIgnoreCase() · contains() · startsWith()";

    /**
     * 숫자 변환. 천 단위 구분자와 앞뒤 공백을 허용한다.
     *
     * @return 변환할 수 없으면 null. strict 산술에서 null 피연산자는 예외가 되므로
     *         해당 행에서 실패한다.
     */
    public Double toNumber(Object v) {
        return Coercion.parseNumber(v);
    }

    /**
     * 숫자 변환. 변환할 수 없으면 {@code fallback}을 반환한다.
     *
     * <p>{@link #toNumber}는 빈 셀에서 행을 실패시킨다. 미입력과 0을 구분할 필요가
     * 없는 경우에 이 함수를 쓴다.
     *
     * <pre>toNumberOr(r.salary, 0) &gt;= 7000</pre>
     *
     * @throws BotCommandException {@code fallback}이 숫자가 아닌 경우
     */
    public Double toNumberOr(Object v, Object fallback) {
        Double parsed = Coercion.parseNumber(v);
        if (parsed != null) {
            return parsed;
        }
        Double defaulted = Coercion.parseNumber(fallback);
        if (defaulted == null) {
            throw new BotCommandException(
                    "toNumberOr 의 기본값이 숫자가 아닙니다: " + fallback);
        }
        return defaulted;
    }

    /** 대소문자를 무시한 문자열 비교. 둘 다 null이면 true. */
    public boolean eqIgnoreCase(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        return text(a).equalsIgnoreCase(text(b));
    }

    /** {@code s}에 {@code sub}가 포함되는지. 대소문자를 구분한다. */
    public boolean contains(Object s, Object sub) {
        if (s == null || sub == null) {
            return false;
        }
        return text(s).contains(text(sub));
    }

    /** null이거나 공백만 있으면 true. */
    public boolean isEmpty(Object v) {
        return v == null || text(v).trim().isEmpty();
    }

    /** {@code s}가 {@code p}로 시작하는지. 대소문자를 구분한다. */
    public boolean startsWith(Object s, Object p) {
        if (s == null || p == null) {
            return false;
        }
        return text(s).startsWith(text(p));
    }

    /**
     * 비교용 문자열. 정수값 Double은 {@code 5.0}이 아닌 {@code 5}로 만든다.
     * 자동 숫자 변환이 켜진 경우 {@code startsWith(Zip, "01")}이 {@code "1234.0"}과
     * 비교되는 것을 막는다.
     */
    private static String text(Object v) {
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
        }
        return String.valueOf(v);
    }
}
