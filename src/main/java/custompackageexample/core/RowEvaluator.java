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
 * 컴파일된 표현식 + 스키마 → 행 단위 평가.
 *
 * <p>{@link #test}는 Boolean 계약이다. Filter / RemoveWhere / Count / Any 가 전부 이걸 쓴다.
 * 나중에 OrderBy를 붙이면 Comparable을 반환하는 {@code key()}가 형제로 추가된다.
 *
 * <p>인스턴스 하나가 액션 실행 1회에 대응한다. 생성 시점에 표현식을 컴파일하므로
 * 문법 오류는 첫 행을 읽기 전에 난다.
 */
public final class RowEvaluator {

    /** 행 번호를 표현식에서 쓸 수 있게 주입하는 이름. 행에는 이름이 없어서 별도 주입이 필요하다. */
    public static final String ROW_INDEX_VAR = "_rowIndex";

    private final String source;
    private final JexlExpression expression;
    private final List<String> columnNames;
    private final boolean autoDetectNumeric;

    /** 평가 횟수. 행마다 정확히 한 번씩만 평가하는지 테스트로 확인하기 위한 카운터. */
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

    /** 봇 개발자가 입력한 원본 표현식. 에러 메시지에 쓴다. */
    public String source() {
        return source;
    }

    /** 이 인스턴스가 표현식을 몇 번 평가했는지. */
    public int evaluationCount() {
        return evaluationCount;
    }

    /**
     * Boolean 계약. 조건식 자리에 {@code Years}라고만 쓰면 6200.0이 나오는데
     * truthy로 취급하면 안 된다. 명확한 예외를 던진다.
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
     * 표현식을 평가하고 원시 결과를 돌려준다. 타입 계약은 호출자가 강제한다.
     *
     * @throws BotCommandException 실패한 행 번호를 붙여서 던진다. 봇 개발자는
     *                             스택트레이스를 볼 수단이 마땅치 않다.
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

    /**
     * 컬럼명 → 값. 열에는 이름이 있고 행에는 없다는 비대칭 때문에
     * 행 번호만 {@code _rowIndex}로 따로 넣는다.
     */
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

    /** {@code 6200.0 [Years]} 처럼 값과 타입을 함께 보여준다. */
    private static String describe(Object result) {
        if (result == null) {
            return "null";
        }
        return result + " (" + result.getClass().getSimpleName() + ")";
    }
}
