package custompackageexample.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.jexl3.JexlScript;
import org.apache.commons.jexl3.MapContext;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.BooleanValue;
import com.automationanywhere.botcommand.data.impl.NumberValue;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.data.model.Schema;
import com.automationanywhere.botcommand.data.model.table.Row;
import com.automationanywhere.botcommand.data.model.table.Table;
import com.automationanywhere.botcommand.exception.BotCommandException;
import com.automationanywhere.botcore.api.dto.AttributeType;

/**
 * 체인 연산의 대상. 표현식에서 {@code table.Where(...).OrderBy(...)} 형태로 호출된다.
 *
 * <p>메서드명이 대문자로 시작하는 것은 자바 관례와 다르다. 이 이름은 자바 API가 아니라
 * 표현식에 직접 입력되는 DSL이며 .NET LINQ와 철자를 맞춘 것이다.
 *
 * <p>액션 하나가 체인 전체를 받으므로 중간 결과를 {@link Table}로 실체화하지 않는다.
 * 연산은 {@code List<Row>} 위에서 이어지고 {@link #toTable()}에서 한 번 테이블이 된다.
 *
 * <p>인스턴스는 액션 실행 1회에 대응한다. 스레드 안전하지 않다.
 */
public final class Queryable {

    private final boolean autoDetectNumeric;

    private List<String> columnNames;
    private Map<String, Integer> columnIndex;
    private List<Row> rows;

    /** 현재 연산 순번. 오류 메시지에 사용한다. */
    private int step;

    public Queryable(Table source, boolean autoDetectNumeric) {
        this.autoDetectNumeric = autoDetectNumeric;
        this.columnNames = new ArrayList<>();
        for (Schema column : source.getSchema()) {
            this.columnNames.add(column.getName());
        }
        this.columnIndex = indexOf(this.columnNames);
        this.rows = new ArrayList<>(source.getRows());
    }

    // ---- 연산자 ----

    /**
     * 조건을 만족하는 행만 남긴다. LINQ {@code Where}에 대응한다.
     *
     * <pre>table.Where(r -&gt; r.Dept == "IT" &amp;&amp; toNumber(r.Age) &gt; 3)</pre>
     */
    public Queryable Where(JexlScript predicate) {
        step++;
        requireLambda(predicate, "Where");
        List<Row> kept = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Object verdict = run(predicate, i, "Where");
            if (!(verdict instanceof Boolean)) {
                throw new BotCommandException(where(i)
                        + " Where 의 조건은 true/false를 반환해야 합니다. 실제: " + describe(verdict));
            }
            if ((Boolean) verdict) {
                kept.add(rows.get(i));
            }
        }
        rows = kept;
        return this;
    }

    /**
     * 키 오름차순 정렬. LINQ {@code OrderBy}에 대응한다.
     *
     * <pre>table.OrderBy(r -&gt; toNumber(r.Age))</pre>
     */
    public Queryable OrderBy(JexlScript key) {
        return sort(key, false);
    }

    /** 키 내림차순 정렬. LINQ {@code OrderByDescending}에 대응한다. */
    public Queryable OrderByDescending(JexlScript key) {
        return sort(key, true);
    }

    /**
     * 컬럼을 선택·계산해 새 스키마로 바꾼다. LINQ {@code Select}에 대응한다.
     *
     * <pre>table.Select(["Name", "Age"], r -&gt; [r.Name, toNumber(r.Age)])</pre>
     *
     * <p>컬럼명을 별도 인자로 받는다. JEXL 맵 리터럴 {@code {'Name': ...}}은
     * {@code HashMap}으로 생성되어 키 순서가 보존되지 않는다.
     */
    // SDK의 Row 생성자가 raw List<Value>를 받는다. List<Value<?>>는 넘길 수 없다.
    @SuppressWarnings("rawtypes")
    public Queryable Select(Object columns, JexlScript projection) {
        step++;
        requireLambda(projection, "Select");
        List<String> names = namesOf(columns);
        List<Row> projected = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Object[] cells = cellsOf(run(projection, i, "Select"), names.size(), i);
            List<Value> out = new ArrayList<>(cells.length);
            for (Object cell : cells) {
                out.add(toValue(cell));
            }
            projected.add(new Row(out));
        }
        columnNames = names;
        columnIndex = indexOf(names);
        rows = projected;
        return this;
    }

    // ---- 종료 ----

    /** 체인 결과를 테이블로 만든다. 실체화는 이 시점에 한 번만 일어난다. */
    public Table toTable() {
        List<Schema> schema = new ArrayList<>(columnNames.size());
        for (int i = 0; i < columnNames.size(); i++) {
            schema.add(new Schema(columnNames.get(i), typeOf(i)));
        }
        return new Table(schema, rows);
    }

    /** 적용된 연산 수. 테스트 전용. */
    public int stepCount() {
        return step;
    }

    // ---- 내부 ----

    private Queryable sort(JexlScript key, boolean descending) {
        step++;
        requireLambda(key, descending ? "OrderByDescending" : "OrderBy");
        String op = descending ? "OrderByDescending" : "OrderBy";

        // 키는 행마다 한 번만 평가한다. 비교자 내부에서 평가하면 O(n log n)회가 된다.
        List<Object[]> decorated = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            decorated.add(new Object[] {run(key, i, op), rows.get(i)});
        }
        // List.sort는 안정 정렬이다. LINQ OrderBy와 동일하게 동점 항목의 순서가 유지된다.
        decorated.sort((a, b) -> {
            int c = compare(a[0], b[0]);
            return descending ? -c : c;
        });

        List<Row> sorted = new ArrayList<>(decorated.size());
        for (Object[] pair : decorated) {
            sorted.add((Row) pair[1]);
        }
        rows = sorted;
        return this;
    }

    /** null이 앞선다. 같은 타입은 자연 순서, 타입이 다르면 문자열로 비교한다. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        if (a == null || b == null) {
            return a == b ? 0 : (a == null ? -1 : 1);
        }
        if (a.getClass() == b.getClass() && a instanceof Comparable) {
            return ((Comparable) a).compareTo(b);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private Object run(JexlScript script, int rowIndex, String op) {
        QueryRow row = new QueryRow(columnIndex, rows.get(rowIndex).getValues(), autoDetectNumeric,
                rowIndex);
        try {
            return script.execute(new MapContext(), row);
        } catch (BotCommandException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BotCommandException(
                    where(rowIndex) + " " + op + " 평가 실패: " + ExpressionEngine.rootCause(e));
        }
    }

    /** 오류 메시지 접두사. 형식: {@code 2번째 연산(3번 행)} */
    private String where(int rowIndex) {
        return step + "번째 연산(" + rowIndex + "번 행)";
    }

    private void requireLambda(JexlScript script, String op) {
        if (script == null) {
            throw new BotCommandException(
                    op + " 에는 람다가 필요합니다. 예: " + op + "(r -> r.Dept == \"IT\")");
        }
    }

    private List<String> namesOf(Object columns) {
        List<String> names = new ArrayList<>();
        if (columns instanceof Object[]) {
            for (Object c : (Object[]) columns) {
                names.add(String.valueOf(c));
            }
        } else if (columns instanceof List) {
            for (Object c : (List<?>) columns) {
                names.add(String.valueOf(c));
            }
        } else if (columns != null) {
            names.add(String.valueOf(columns));
        }
        if (names.isEmpty()) {
            throw new BotCommandException("Select 의 첫 인자로 컬럼명 목록이 필요합니다. "
                    + "예: Select([\"Name\", \"Age\"], r -> [r.Name, r.Age])");
        }
        return names;
    }

    private Object[] cellsOf(Object produced, int expected, int rowIndex) {
        Object[] cells;
        if (produced instanceof Object[]) {
            cells = (Object[]) produced;
        } else if (produced instanceof List) {
            cells = ((List<?>) produced).toArray();
        } else {
            cells = new Object[] {produced};
        }
        if (cells.length != expected) {
            throw new BotCommandException(where(rowIndex) + " Select 의 컬럼명은 " + expected
                    + "개인데 값은 " + cells.length + "개입니다.");
        }
        return cells;
    }

    /** 비어 있지 않은 첫 값의 자바 타입으로 컬럼 타입을 정한다. 값이 없으면 STRING. */
    @SuppressWarnings("rawtypes")
    private AttributeType typeOf(int column) {
        for (Row row : rows) {
            List<Value> values = row.getValues();
            if (column >= values.size() || values.get(column) == null) {
                continue;
            }
            Object raw = values.get(column).get();
            if (raw instanceof Double) {
                return AttributeType.NUMBER;
            }
            if (raw instanceof Boolean) {
                return AttributeType.BOOLEAN;
            }
            return AttributeType.STRING;
        }
        return AttributeType.STRING;
    }

    private static Value<?> toValue(Object cell) {
        if (cell instanceof Boolean) {
            return new BooleanValue((Boolean) cell);
        }
        if (cell instanceof Number) {
            return new NumberValue(((Number) cell).doubleValue());
        }
        return new StringValue(cell == null ? "" : String.valueOf(cell));
    }

    private static Map<String, Integer> indexOf(List<String> names) {
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            index.put(names.get(i), i);
        }
        return index;
    }

    private static String describe(Object result) {
        if (result == null) {
            return "null";
        }
        return result + " (" + result.getClass().getSimpleName() + ")";
    }
}
