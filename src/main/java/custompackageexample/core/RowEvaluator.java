package custompackageexample.core;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.MapContext;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.model.Schema;
import com.automationanywhere.botcommand.data.model.table.Row;
import com.automationanywhere.botcommand.exception.BotCommandException;

/**
 * 컴파일된 표현식과 스키마로 행을 평가한다. 자유 변수 방식({@link custompackageexample.command.FilterToTable})
 * 전용이며, 람다 방식은 {@link Queryable}이 담당한다.
 *
 * <p>{@link #test}는 Boolean 계약이다. {@link TableOps#filter}가 이를 통해 조건을 판정한다.
 *
 * <p>인스턴스는 액션 실행 1회에 대응한다. 생성 시점에 컴파일하므로 문법 오류는
 * 첫 행을 읽기 전에 발생한다.
 */
public final class RowEvaluator {

    /** 행 번호를 바인딩하는 이름. 열과 달리 행에는 이름이 없어 별도로 주입한다. */
    public static final String ROW_INDEX_VAR = "_rowIndex";

    private final String source;
    private final JexlExpression expression;
    private final List<String> columnNames;
    private final boolean autoDetectNumeric;

    /** 평가 횟수. 행당 1회인지 검증하기 위한 카운터. */
    private int evaluationCount;

    public RowEvaluator(String source, List<Schema> schema, boolean autoDetectNumeric) {
        this.source = source;
        this.expression = ExpressionEngine.compile(source);
        this.columnNames = new ArrayList<>(schema.size());
        for (Schema column : schema) {
            this.columnNames.add(column.getName());
        }
        this.autoDetectNumeric = autoDetectNumeric;
    }

    /** 이 인스턴스의 평가 횟수. */
    public int evaluationCount() {
        return evaluationCount;
    }

    /**
     * Boolean 계약. 조건식 자리에 컬럼명만 쓰면 값 자체가 반환되므로,
     * truthy 판정 대신 예외로 처리한다.
     */
    public boolean test(Row row, int rowIndex) {
        Object result = evaluate(row, rowIndex);
        if (result instanceof Boolean) {
            return (Boolean) result;
        }
        throw new BotCommandException(
                "조건식은 true/false를 반환해야 합니다. 실제: " + describe(result) + " [" + source + "]");
    }

    /**
     * 표현식을 평가해 원시 결과를 반환한다. 타입 계약은 호출자가 강제한다.
     *
     * @throws BotCommandException 평가 실패. 메시지에 행 번호를 포함한다.
     */
    private Object evaluate(Row row, int rowIndex) {
        evaluationCount++;
        try {
            return expression.evaluate(contextFor(row, rowIndex));
        } catch (BotCommandException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BotCommandException(
                    rowIndex + "번 행 평가 실패 [" + source + "]: " + ExpressionEngine.rootCause(e));
        }
    }

    /** 컬럼명을 값에 바인딩한다. 행 번호는 {@code _rowIndex}로 별도 바인딩한다. */
    // SDK의 Row.getValues()가 raw List<Value>를 반환한다. List<Value<?>>로는 받을 수 없다.
    @SuppressWarnings("rawtypes")
    private MapContext contextFor(Row row, int rowIndex) {
        MapContext context = new MapContext();
        List<Value> values = row.getValues();
        for (int i = 0; i < columnNames.size(); i++) {
            Object bound = i < values.size()
                    ? Coercion.toJava(values.get(i), autoDetectNumeric)
                    : null;
            context.set(columnNames.get(i), bound);
        }
        context.set(ROW_INDEX_VAR, rowIndex);
        return context;
    }

    /** 오류 메시지용. 값과 타입을 함께 표기한다. */
    private static String describe(Object result) {
        if (result == null) {
            return "null";
        }
        return result + " (" + result.getClass().getSimpleName() + ")";
    }
}
