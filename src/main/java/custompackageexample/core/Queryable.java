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
 * <p>메서드명은 .NET LINQ의 철자를 따른다. 자바 API가 아니라 표현식에 입력되는 DSL이다.
 *
 * <p>연산자 메서드는 단계를 등록만 하고 행을 읽지 않는다. 실행은 {@link #toTable()}에서
 * 한 번 일어나며, 원본의 각 행이 단계 체인을 통과한다. {@code Take}가 목표 개수를 채우면
 * 중단 신호가 위로 전파되어 남은 행은 읽지 않는다.
 *
 * <p>{@code OrderBy}는 전체 입력이 있어야 첫 행을 낼 수 있으므로 그 지점에서 버퍼링한다.
 * 앞선 단계의 조기 종료는 여기까지만 유효하다.
 *
 * <p>컬럼 구성은 체인 구축 시점에 확정된다. {@code Select} 뒤의 단계는 새 컬럼명으로
 * 해석되며, 컬럼명 오류는 행을 읽기 전에 드러난다.
 *
 * <p>인스턴스는 액션 실행 1회에 대응한다. 스레드 안전하지 않다.
 */
public final class Queryable {

    private final Table source;
    private final boolean autoDetectNumeric;
    private final List<StageFactory> stages = new ArrayList<>();

    /** 체인 구축 중의 컬럼 구성. {@code Select}가 이 값을 바꾼다. */
    private List<String> columns;
    private Map<String, Integer> columnIndex;

    /** 등록된 연산 수. 오류 메시지의 순번이 된다. */
    private int step;

    /** {@link #First()}가 설정한다. 결과가 0건이면 오류로 처리한다. */
    private boolean requireNonEmpty;

    /** 원본에서 실제로 읽은 행 수. 조기 종료 검증용. */
    private int scanned;

    public Queryable(Table source, boolean autoDetectNumeric) {
        this.source = source;
        this.autoDetectNumeric = autoDetectNumeric;
        this.columns = new ArrayList<>();
        for (Schema column : source.getSchema()) {
            this.columns.add(column.getName());
        }
        this.columnIndex = indexOf(this.columns);
    }

    // ---- 연산자 ----

    /**
     * 조건을 만족하는 행만 남긴다. LINQ {@code Where}에 대응한다.
     *
     * <pre>table.Where(r -&gt; r.Dept == "IT" &amp;&amp; toNumber(r.Age) &gt; 3)</pre>
     */
    public Queryable Where(JexlScript predicate) {
        Lambda test = lambda(predicate, "Where");
        stages.add(next -> new Stage(next) {
            @Override
            boolean push(Row row) {
                Object verdict = test.eval(row, seen++);
                if (!(verdict instanceof Boolean)) {
                    throw new BotCommandException(test.at(seen - 1)
                            + " Where 의 조건은 true/false를 반환해야 합니다. 실제: "
                            + describe(verdict));
                }
                return (Boolean) verdict ? next.push(row) : true;
            }
        });
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
    public Queryable Select(Object columnNames, JexlScript projection) {
        Lambda project = lambda(projection, "Select");
        List<String> produced = namesOf(columnNames);
        stages.add(next -> new Stage(next) {
            @Override
            boolean push(Row row) {
                int index = seen++;
                Object[] cells = cellsOf(project.eval(row, index), produced.size(), project, index);
                return next.push(rowOf(cells));
            }
        });
        columns = produced;
        columnIndex = indexOf(produced);
        return this;
    }

    /**
     * 앞에서 {@code n}개만 남긴다. LINQ {@code Take}에 대응한다.
     *
     * <p>{@code n}개를 채우면 원본 읽기를 중단한다. 앞에 {@code OrderBy}가 있으면
     * 정렬이 전체 입력을 요구하므로 중단 지점은 정렬 이후가 된다.
     *
     * <pre>table.Where(r -&gt; r.Dept == "IT").Take(10)</pre>
     */
    public Queryable Take(Object n) {
        return limit(countOf(n, "Take"), "Take");
    }

    /**
     * 앞에서 {@code n}개를 건너뛴다. LINQ {@code Skip}에 대응한다.
     *
     * <pre>table.Skip(10).Take(10)</pre>
     */
    public Queryable Skip(Object n) {
        int count = countOf(n, "Skip");
        step++;
        stages.add(next -> new Stage(next) {
            private int skipped;

            @Override
            boolean push(Row row) {
                if (skipped < count) {
                    skipped++;
                    return true;
                }
                return next.push(row);
            }
        });
        return this;
    }

    /**
     * 첫 행만 남긴다. LINQ {@code First}에 대응한다. 결과는 1행짜리 테이블이다.
     *
     * <p>조건을 만족하는 행이 없으면 오류다. 0건을 허용하려면
     * {@link #FirstOrDefault()}를 쓴다.
     */
    public Queryable First() {
        requireNonEmpty = true;
        return limit(1, "First");
    }

    /** 첫 행만 남긴다. 0건이면 빈 테이블이다. LINQ {@code FirstOrDefault}에 대응한다. */
    public Queryable FirstOrDefault() {
        return limit(1, "FirstOrDefault");
    }

    // ---- 종료 연산자 ----

    /** 행 수. LINQ {@code Count}에 대응한다. */
    public Double Count() {
        return (double) execute().size();
    }

    /**
     * 행이 하나라도 있으면 true. LINQ {@code Any}에 대응한다.
     *
     * <p>첫 행에서 원본 읽기를 중단한다.
     */
    public Boolean Any() {
        Presence presence = new Presence();
        run(presence);
        return presence.found;
    }

    /**
     * 조건을 만족하는 행이 하나라도 있으면 true. LINQ {@code Any}에 대응한다.
     *
     * <p>첫 번째 true에서 원본 읽기를 중단한다.
     */
    public Boolean Any(JexlScript predicate) {
        Verdict verdict = new Verdict(lambda(predicate, "Any"), true);
        run(verdict);
        return verdict.stopped;
    }

    /**
     * 모든 행이 조건을 만족하면 true. LINQ {@code All}에 대응한다.
     *
     * <p>첫 번째 false에서 원본 읽기를 중단한다. 대상 행이 없으면 true다.
     */
    public Boolean All(JexlScript predicate) {
        Verdict verdict = new Verdict(lambda(predicate, "All"), false);
        run(verdict);
        return !verdict.stopped;
    }

    /** 합계. 빈 결과는 0이다. LINQ {@code Sum}에 대응한다. */
    public Double Sum(JexlScript selector) {
        List<Double> values = numbers(selector, "Sum");
        double total = 0;
        for (Double v : values) {
            total += v;
        }
        return total;
    }

    /** 평균. LINQ {@code Average}에 대응한다. */
    public Double Average(JexlScript selector) {
        List<Double> values = requireValues(numbers(selector, "Average"), "Average");
        double total = 0;
        for (Double v : values) {
            total += v;
        }
        return total / values.size();
    }

    /** 최솟값. LINQ {@code Min}에 대응한다. */
    public Double Min(JexlScript selector) {
        List<Double> values = requireValues(numbers(selector, "Min"), "Min");
        double min = values.get(0);
        for (Double v : values) {
            min = Math.min(min, v);
        }
        return min;
    }

    /** 최댓값. LINQ {@code Max}에 대응한다. */
    public Double Max(JexlScript selector) {
        List<Double> values = requireValues(numbers(selector, "Max"), "Max");
        double max = values.get(0);
        for (Double v : values) {
            max = Math.max(max, v);
        }
        return max;
    }

    // ---- 실행 ----

    /**
     * 체인을 실행해 테이블을 만든다. 원본은 이 시점에 처음 읽힌다.
     */
    public Table toTable() {
        List<Row> rows = execute();
        List<Schema> schema = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            schema.add(new Schema(columns.get(i), typeOf(rows, i)));
        }
        return new Table(schema, rows);
    }

    /** 원본을 종료 단계까지 흘려보낸다. 종료 단계가 false를 반환하면 읽기를 멈춘다. */
    private void run(Stage terminal) {
        Stage head = terminal;
        for (int i = stages.size() - 1; i >= 0; i--) {
            head = stages.get(i).create(head);
        }
        scanned = 0;
        for (Row row : source.getRows()) {
            scanned++;
            if (!head.push(row)) {
                break;
            }
        }
        head.end();
    }

    private List<Row> execute() {
        Collector collector = new Collector();
        run(collector);
        if (requireNonEmpty && collector.rows.isEmpty()) {
            throw new BotCommandException("First: 조건을 만족하는 행이 없습니다. "
                    + "0건을 허용하려면 FirstOrDefault() 를 쓰십시오.");
        }
        return collector.rows;
    }

    private Queryable limit(int max, String op) {
        step++;
        stages.add(next -> new Stage(next) {
            private int taken;

            @Override
            boolean push(Row row) {
                if (taken >= max) {
                    return false;
                }
                taken++;
                return next.push(row) && taken < max;
            }
        });
        return this;
    }

    /** 결과 행마다 선택자를 평가해 숫자 목록을 만든다. */
    private List<Double> numbers(JexlScript selector, String op) {
        Lambda value = lambda(selector, op);
        List<Row> rows = execute();
        List<Double> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Object produced = value.eval(rows.get(i), i);
            Double number = Coercion.parseNumber(produced);
            if (number == null) {
                throw new BotCommandException(value.at(i) + " " + op
                        + " 의 값이 숫자가 아닙니다: " + describe(produced));
            }
            out.add(number);
        }
        return out;
    }

    private static List<Double> requireValues(List<Double> values, String op) {
        if (values.isEmpty()) {
            throw new BotCommandException(op + ": 대상 행이 없습니다.");
        }
        return values;
    }

    /** 원본에서 읽은 행 수. {@link #toTable()} 이후에 유효하다. 테스트 전용. */
    public int scannedRows() {
        return scanned;
    }

    // ---- 단계 ----

    /** 다음 단계를 받아 자신을 만든다. 체인은 뒤에서 앞으로 조립된다. */
    private interface StageFactory {
        Stage create(Stage next);
    }

    /**
     * 행 하나를 처리하는 단계.
     *
     * <p>{@link #push}의 반환값이 false이면 상류는 공급을 멈춘다.
     */
    private abstract static class Stage {

        final Stage next;

        /** 이 단계가 지금까지 받은 행 수. 다음 행의 {@code _rowIndex}가 된다. */
        int seen;

        Stage(Stage next) {
            this.next = next;
        }

        abstract boolean push(Row row);

        /** 입력 종료. 버퍼링하는 단계가 이 시점에 하류로 밀어낸다. */
        void end() {
            if (next != null) {
                next.end();
            }
        }
    }

    /** 체인의 끝. 결과 행을 모은다. */
    private static final class Collector extends Stage {

        final List<Row> rows = new ArrayList<>();

        Collector() {
            super(null);
        }

        @Override
        boolean push(Row row) {
            rows.add(row);
            return true;
        }
    }

    /** 행이 하나라도 도달했는지만 본다. 도달 즉시 중단한다. */
    private static final class Presence extends Stage {

        boolean found;

        Presence() {
            super(null);
        }

        @Override
        boolean push(Row row) {
            found = true;
            return false;
        }
    }

    /**
     * 조건을 평가하다가 {@code stopOn}과 같은 결과가 나오면 중단한다.
     *
     * <p>{@code Any}는 true에서, {@code All}은 false에서 멈춘다.
     */
    private final class Verdict extends Stage {

        private final Lambda test;
        private final boolean stopOn;

        boolean stopped;

        private Verdict(Lambda test, boolean stopOn) {
            super(null);
            this.test = test;
            this.stopOn = stopOn;
        }

        @Override
        boolean push(Row row) {
            int index = seen++;
            Object result = test.eval(row, index);
            if (!(result instanceof Boolean)) {
                throw new BotCommandException(test.at(index) + " " + test.op
                        + " 의 조건은 true/false를 반환해야 합니다. 실제: " + describe(result));
            }
            if ((Boolean) result == stopOn) {
                stopped = true;
                return false;
            }
            return true;
        }
    }

    private Queryable sort(JexlScript key, boolean descending) {
        Lambda keyOf = lambda(key, descending ? "OrderByDescending" : "OrderBy");
        stages.add(next -> new Stage(next) {
            private final List<Object[]> buffer = new ArrayList<>();

            @Override
            boolean push(Row row) {
                buffer.add(new Object[] {keyOf.eval(row, seen++), row});
                return true;
            }

            @Override
            void end() {
                // List.sort는 안정 정렬이다. LINQ OrderBy와 동일하게 동점 항목의 순서가 유지된다.
                buffer.sort((a, b) -> {
                    int c = compare(a[0], b[0]);
                    return descending ? -c : c;
                });
                for (Object[] pair : buffer) {
                    if (!next.push((Row) pair[1])) {
                        break;
                    }
                }
                next.end();
            }
        });
        return this;
    }

    // ---- 람다 실행 ----

    /**
     * 체인 구축 시점에 확정되는 람다 실행 정보.
     *
     * <p>컬럼 구성과 연산 순번을 이 시점에 붙잡는다. {@code Select} 뒤의 단계는
     * 새 컬럼 구성으로 해석된다.
     */
    private final class Lambda {

        private final JexlScript script;
        private final Map<String, Integer> columnsAt;
        private final int stepAt;
        private final String op;

        private Lambda(JexlScript script, Map<String, Integer> columnsAt, int stepAt, String op) {
            this.script = script;
            this.columnsAt = columnsAt;
            this.stepAt = stepAt;
            this.op = op;
        }

        @SuppressWarnings("rawtypes")
        private Object eval(Row row, int rowIndex) {
            List<Value> values = row.getValues();
            QueryRow view = new QueryRow(columnsAt, values, autoDetectNumeric, rowIndex);
            try {
                return script.execute(new MapContext(), view);
            } catch (BotCommandException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new BotCommandException(
                        at(rowIndex) + " " + op + " 평가 실패: " + ExpressionEngine.rootCause(e));
            }
        }

        /** 오류 메시지 접두사. 형식: {@code 2번째 연산(3번 행)} */
        private String at(int rowIndex) {
            return stepAt + "번째 연산(" + rowIndex + "번 행)";
        }
    }

    private Lambda lambda(JexlScript script, String op) {
        if (script == null) {
            throw new BotCommandException(
                    op + " 에는 람다가 필요합니다. 예: " + op + "(r -> r.Dept == \"IT\")");
        }
        return new Lambda(script, columnIndex, ++step, op);
    }

    // ---- 인자 해석 ----

    private static List<String> namesOf(Object columnNames) {
        List<String> names = new ArrayList<>();
        if (columnNames instanceof Object[]) {
            for (Object c : (Object[]) columnNames) {
                names.add(String.valueOf(c));
            }
        } else if (columnNames instanceof List) {
            for (Object c : (List<?>) columnNames) {
                names.add(String.valueOf(c));
            }
        } else if (columnNames != null) {
            names.add(String.valueOf(columnNames));
        }
        if (names.isEmpty()) {
            throw new BotCommandException("Select 의 첫 인자로 컬럼명 목록이 필요합니다. "
                    + "예: Select([\"Name\", \"Age\"], r -> [r.Name, r.Age])");
        }
        return names;
    }

    private static int countOf(Object n, String op) {
        Double parsed = Coercion.parseNumber(n);
        if (parsed == null || parsed != Math.floor(parsed) || parsed < 0) {
            throw new BotCommandException(
                    op + " 에는 0 이상의 정수가 필요합니다. 실제: " + describe(n));
        }
        return parsed.intValue();
    }

    private static Object[] cellsOf(Object produced, int expected, Lambda origin, int rowIndex) {
        Object[] cells;
        if (produced instanceof Object[]) {
            cells = (Object[]) produced;
        } else if (produced instanceof List) {
            cells = ((List<?>) produced).toArray();
        } else {
            cells = new Object[] {produced};
        }
        if (cells.length != expected) {
            throw new BotCommandException(origin.at(rowIndex) + " Select 의 컬럼명은 " + expected
                    + "개인데 값은 " + cells.length + "개입니다.");
        }
        return cells;
    }

    // ---- 값 변환 ----

    // SDK의 Row 생성자가 raw List<Value>를 받는다. List<Value<?>>는 넘길 수 없다.
    @SuppressWarnings("rawtypes")
    private static Row rowOf(Object[] cells) {
        List<Value> out = new ArrayList<>(cells.length);
        for (Object cell : cells) {
            out.add(toValue(cell));
        }
        return new Row(out);
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

    /** 비어 있지 않은 첫 값의 자바 타입으로 컬럼 타입을 정한다. 값이 없으면 STRING. */
    @SuppressWarnings("rawtypes")
    private static AttributeType typeOf(List<Row> rows, int column) {
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

    private static Map<String, Integer> indexOf(List<String> names) {
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            index.put(names.get(i), i);
        }
        return index;
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        return value + " (" + value.getClass().getSimpleName() + ")";
    }
}
