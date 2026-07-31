package custompackageexample.core;

import java.math.BigDecimal;

import com.automationanywhere.botcommand.data.Value;

/**
 * SDK {@link Value}를 JEXL 컨텍스트에 바인딩할 자바 객체로 변환한다.
 *
 * <p>기본값은 문자열 유지다. 숫자 비교는 {@code toNumber(Years) > 3}으로 명시한다.
 * 자동 변환은 우편번호 {@code 01234}, 사번 {@code 0087}의 앞자리 0을 잃게 하므로
 * 기본으로 켜지 않는다.
 */
public final class Coercion {

    private Coercion() {
    }

    /**
     * @param autoDetectNumeric true이면 숫자로 읽히는 문자열을 Double로 바인딩한다.
     *                          앞자리 0이 의미를 갖는 값({@code 0087})은 문자열로 유지한다.
     */
    public static Object toJava(Value<?> value, boolean autoDetectNumeric) {
        if (value == null) {
            return null;
        }
        Object raw = value.get();
        if (raw == null) {
            return null;
        }
        if (raw instanceof Double || raw instanceof Boolean) {
            return raw;
        }
        if (raw instanceof Number) {
            return ((Number) raw).doubleValue();
        }
        if (!(raw instanceof String)) {
            // DateTime 등은 지원 범위 밖이다. 문자열로 변환해 비교만 가능하게 둔다.
            return String.valueOf(raw);
        }
        String s = (String) raw;
        if (autoDetectNumeric && !hasSignificantLeadingZero(s)) {
            Double n = parseNumber(s);
            if (n != null) {
                return n;
            }
        }
        return s;
    }

    /**
     * 명시적 숫자 변환. {@link Fn#toNumber}의 구현이다.
     * 천 단위 구분자와 앞뒤 공백을 허용한다.
     *
     * @return 변환할 수 없으면 null
     */
    public static Double parseNumber(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof Boolean) {
            return null;
        }
        String s = String.valueOf(v).trim().replace(",", "");
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s).doubleValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * {@code "0087"}, {@code "01234"}는 true. {@code "0"}, {@code "0.5"}는 false.
     * 앞자리 0이 값의 의미를 결정하는 경우만 선별한다.
     */
    private static boolean hasSignificantLeadingZero(String s) {
        String t = s.trim();
        if (t.length() < 2 || t.charAt(0) != '0') {
            return false;
        }
        return t.charAt(1) != '.';
    }
}
