package custompackageexample.core;

import java.util.List;
import java.util.Map;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.exception.BotCommandException;

/**
 * 람다에 전달되는 행. 표현식에서 {@code r.Dept} 로 셀 값에 접근한다.
 *
 * <p>정의되지 않은 컬럼에 예외를 던지는 것이 이 클래스의 역할이다. JEXL의
 * {@code strict(true)}는 컨텍스트 변수에만 적용되고 property 접근에는 적용되지 않는다.
 * {@code get()}이 null을 반환하면 컬럼명 오타가 조건식에서 false로 평가되어 결과가
 * 0건이 되고 오류는 발생하지 않는다.
 *
 * <p>{@code r.Dept}와 {@code r['Dept']}는 모두 {@link #get(String)}으로 들어온다.
 */
public final class QueryRow {

    /** 행 번호에 접근하기 위한 이름. 열과 달리 행에는 이름이 없어 별도로 제공한다. */
    public static final String ROW_INDEX = "_rowIndex";

    private final Map<String, Integer> columnIndex;

    // SDK의 Row.getValues()가 raw List<Value>를 반환한다. List<Value<?>>로는 받을 수 없다.
    @SuppressWarnings("rawtypes")
    private final List<Value> values;

    private final boolean autoDetectNumeric;
    private final int rowIndex;

    @SuppressWarnings("rawtypes")
    QueryRow(Map<String, Integer> columnIndex, List<Value> values, boolean autoDetectNumeric,
            int rowIndex) {
        this.columnIndex = columnIndex;
        this.values = values;
        this.autoDetectNumeric = autoDetectNumeric;
        this.rowIndex = rowIndex;
    }

    /**
     * 컬럼명으로 셀 값을 반환한다.
     *
     * @throws BotCommandException 해당 컬럼이 없는 경우. 메시지에 사용 가능한 컬럼명을 포함한다.
     */
    public Object get(String name) {
        if (ROW_INDEX.equals(name)) {
            return rowIndex;
        }
        Integer i = columnIndex.get(name);
        if (i == null) {
            throw new BotCommandException("컬럼 '" + name + "' 이(가) 테이블에 없습니다. "
                    + "사용 가능한 컬럼: " + columnIndex.keySet());
        }
        // 스키마보다 셀 수가 적은 행이 들어올 수 있다. 없는 칸은 null이다.
        return i < values.size() ? Coercion.toJava(values.get(i), autoDetectNumeric) : null;
    }
}
