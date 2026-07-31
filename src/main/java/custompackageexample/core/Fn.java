package custompackageexample.core;

/**
 * 표현식에서 호출할 수 있는 함수 화이트리스트.
 *
 * <p>JEXL 네임스페이스로 {@code null} 키에 등록되므로 봇 개발자는 접두사 없이
 * {@code toNumber(Salary) > 5000} 처럼 쓴다. 여기에 없는 메서드는 호출할 수 없다.
 *
 * <p>모든 파라미터가 {@code Object}인 이유: 컬럼 값은 Coercion을 거쳐 String / Double /
 * Boolean 중 무엇으로든 올 수 있고, 봇 개발자가 타입을 신경 쓰지 않아도 되게 하려는 것.
 */
public class Fn {

    /**
     * 숫자로 변환한다. {@code "1,200"}, {@code " 42 "}, {@code "0087"} 모두 처리한다.
     *
     * @return 숫자로 읽을 수 없으면 null. strict 산술에서 null 비교는 예외가 되므로
     *         "조용히 0으로 취급"되는 대신 해당 행에서 명확히 실패한다.
     */
    public Double toNumber(Object v) {
        return Coercion.parseNumber(v);
    }

    /** 대소문자를 무시한 문자열 동등 비교. 둘 다 null이면 true. */
    public boolean eqIgnoreCase(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        return text(a).equalsIgnoreCase(text(b));
    }

    /** {@code s}가 {@code sub}를 포함하는지. 대소문자를 구분한다. */
    public boolean contains(Object s, Object sub) {
        if (s == null || sub == null) {
            return false;
        }
        return text(s).contains(text(sub));
    }

    /** null이거나 공백만 있으면 true. 빈 셀 판별용. */
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
     * 표시용 문자열. Double이 정수값이면 {@code 5.0} 대신 {@code 5}로 만든다.
     * 자동 숫자 변환을 켠 테이블에서 {@code startsWith(Zip, "01")}이
     * {@code "1234.0"}과 비교되는 사고를 줄이기 위한 것.
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
