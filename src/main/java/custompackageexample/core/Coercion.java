package custompackageexample.core;

import java.math.BigDecimal;

import com.automationanywhere.botcommand.data.Value;

/**
 * SDK {@link Value} → JEXL 컨텍스트에 바인딩할 Java 객체.
 *
 * <p>기본 동작은 "문자열은 문자열로 둔다"다. 숫자 비교는 봇 개발자가
 * {@code toNumber(Years) > 3}으로 명시한다. 자동 변환을 켜면 편하지만
 * 우편번호 {@code 01234}, 사번 {@code 0087}의 앞 0이 날아가기 때문.
 */
public final class Coercion {

    private Coercion() {
    }

    /**
     * @param autoDetectNumeric 켜면 숫자로 읽히는 문자열을 Double로 바인딩한다.
     *                          단 앞자리 0이 의미를 갖는 값({@code 0087})은 문자열로 남긴다.
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
            // DateTime 등은 이 과제 범위 밖. 문자열로 눕혀서 비교는 되게 한다.
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
     * 명시적 숫자 변환. {@code Fn#toNumber}의 실제 구현.
     * 천 단위 콤마와 앞뒤 공백을 허용한다.
     *
     * @return 읽을 수 없으면 null
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
     * {@code "0087"}, {@code "01234"} → true. {@code "0"}, {@code "0.5"} → false.
     * 앞 0이 날아가면 값의 뜻이 바뀌는 경우만 골라낸다.
     */
    private static boolean hasSignificantLeadingZero(String s) {
        String t = s.trim();
        if (t.length() < 2 || t.charAt(0) != '0') {
            return false;
        }
        return t.charAt(1) != '.';
    }
}
