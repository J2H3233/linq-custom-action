package custompackageexample.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.automationanywhere.botcommand.data.model.table.Table;
import com.automationanywhere.botcommand.exception.BotCommandException;
import com.automationanywhere.botcore.api.dto.AttributeType;

/** 람다 체인 질의(LINQ 메서드 구문)의 동작과 보안 검증. */
class QueryTest {

    private static Table query(Table source, String chain) {
        return query(source, chain, false);
    }

    private static Table query(Table source, String chain, boolean autoNumeric) {
        return ExpressionEngine.evaluateQuery(chain, new Queryable(source, autoNumeric)).toTable();
    }

    private static List<String> names(String chain) {
        return Tables.column(query(Tables.employees(), chain), "Name");
    }

    private static BotCommandException failure(String chain) {
        return assertThrows(BotCommandException.class, () -> query(Tables.employees(), chain));
    }

    // ---- Where ----

    @Test
    void whereTakesALambda() {
        assertEquals(Arrays.asList("Ann", "Cho", "Dan"),
                names("table.Where(r -> r.Dept == \"IT\")"));
    }

    @Test
    void lambdaCanCallWhitelistedFunctions() {
        assertEquals(Arrays.asList("Ann", "Dan"),
                names("table.Where(r -> r.Dept == \"IT\" && toNumber(r.Years) > 3)"));
    }

    @Test
    void rowIndexIsReachableThroughTheRow() {
        assertEquals(Arrays.asList("Ann", "Bob"), names("table.Where(r -> r._rowIndex < 2)"));
    }

    @Test
    void autoDetectNumericWorksInsideLambda() {
        Table out = query(Tables.employees(), "table.Where(r -> r.Years > 3)", true);
        assertEquals(Arrays.asList("Ann", "Dan", "Eve"), Tables.column(out, "Name"));
    }

    /**
     * 소문자 컬럼명. {@code name} / {@code id}는 자바 getter 이름과 겹칠 수 있어
     * property 해석 경로가 달라질 여지가 있다.
     */
    @Test
    void lowercaseColumnNamesResolve() {
        Table t = Tables.of(Arrays.asList("dept", "name", "id", "age"),
                new String[] {"부서1", "김철수", "1111111", "23"},
                new String[] {"부서2", "강민수", "2222222", "32"},
                new String[] {"부서3", "박태영", "3333333", "42"},
                new String[] {"부서1", "안상현", "44444444", "45"});

        assertEquals(Arrays.asList("김철수", "안상현"),
                Tables.column(query(t, "table.Where(r -> r.dept == \"부서1\")"), "name"));
        assertEquals(Arrays.asList("강민수"),
                Tables.column(query(t, "table.Where(r -> r.id == \"2222222\")"), "name"));
        assertEquals(Arrays.asList("안상현", "박태영", "강민수"),
                Tables.column(query(t,
                        "table.Where(r -> toNumber(r.age) >= 30)"
                                + ".OrderByDescending(r -> toNumber(r.age))"), "name"));
    }

    // ---- OrderBy ----

    @Test
    void orderBySortsAscending() {
        assertEquals(Arrays.asList("Cho", "Bob", "Eve", "Ann", "Dan"),
                names("table.OrderBy(r -> toNumber(r.Years))"));
    }

    @Test
    void orderByDescendingReversesIt() {
        assertEquals(Arrays.asList("Dan", "Ann", "Eve", "Bob", "Cho"),
                names("table.OrderByDescending(r -> toNumber(r.Years))"));
    }

    /** 동점 항목의 원래 순서가 유지된다. LINQ OrderBy와 같은 성질이다. */
    @Test
    void orderByIsStable() {
        // HR < IT < Sales. IT 그룹 내부는 원래 순서인 Ann, Cho, Dan 이 유지된다.
        assertEquals(Arrays.asList("Bob", "Ann", "Cho", "Dan", "Eve"),
                names("table.OrderBy(r -> r.Dept)"));
    }

    /** 문자열 정렬은 사전순이다. "10"이 "9"보다 앞선다. 숫자 정렬에는 toNumber가 필요하다. */
    @Test
    void stringSortIsLexicographicNotNumeric() {
        Table t = Tables.of(Arrays.asList("Name", "N"),
                new String[] {"Ann", "9"},
                new String[] {"Bob", "10"});
        assertEquals(Arrays.asList("Bob", "Ann"),
                Tables.column(query(t, "table.OrderBy(r -> r.N)"), "Name"));
        assertEquals(Arrays.asList("Ann", "Bob"),
                Tables.column(query(t, "table.OrderBy(r -> toNumber(r.N))"), "Name"));
    }

    // ---- Select ----

    @Test
    void selectPicksColumnsAndKeepsTheGivenOrder() {
        Table out = query(Tables.employees(),
                "table.Select([\"Years\", \"Name\"], r -> [toNumber(r.Years), r.Name])");
        assertEquals(2, out.getSchema().size());
        assertEquals("Years", out.getSchema().get(0).getName());
        assertEquals("Name", out.getSchema().get(1).getName());
        assertEquals(Arrays.asList("Ann", "Bob", "Cho", "Dan", "Eve"), Tables.column(out, "Name"));
    }

    @Test
    void selectInfersColumnTypeFromTheProducedValue() {
        Table out = query(Tables.employees(),
                "table.Select([\"Name\", \"Years\"], r -> [r.Name, toNumber(r.Years)])");
        assertEquals(AttributeType.STRING, out.getSchema().get(0).getType());
        assertEquals(AttributeType.NUMBER, out.getSchema().get(1).getType());
    }

    @Test
    void selectCanComputeNewValues() {
        Table out = query(Tables.employees(),
                "table.Where(r -> r.Name == \"Ann\")"
                        + ".Select([\"Label\"], r -> [r.Name + \"/\" + r.Dept])");
        assertEquals(Arrays.asList("Ann/IT"), Tables.column(out, "Label"));
    }

    @Test
    void selectRejectsColumnCountMismatch() {
        BotCommandException e =
                failure("table.Select([\"A\", \"B\"], r -> [r.Name])");
        assertTrue(e.getMessage().contains("컬럼명은 2개인데 값은 1개"), e.getMessage());
    }

    // ---- 체이닝 ----

    @Test
    void operatorsChain() {
        Table out = query(Tables.employees(),
                "table.Where(r -> r.Dept == \"IT\")"
                        + ".OrderByDescending(r -> toNumber(r.Salary))"
                        + ".Select([\"Name\", \"Salary\"], r -> [r.Name, toNumber(r.Salary)])");
        assertEquals(Arrays.asList("Dan", "Ann", "Cho"), Tables.column(out, "Name"));
        assertEquals(2, out.getSchema().size());
    }

    @Test
    void selectedColumnsAreUsableByLaterOperators() {
        Table out = query(Tables.employees(),
                "table.Select([\"Who\"], r -> [r.Name]).Where(r -> r.Who == \"Dan\")");
        assertEquals(Arrays.asList("Dan"), Tables.column(out, "Who"));
    }

    @Test
    void sourceTableIsNotModified() {
        Table source = Tables.employees();
        query(source, "table.Where(r -> r.Dept == \"IT\")");
        assertEquals(5, source.getRows().size());
        assertEquals(4, source.getSchema().size());
    }

    @Test
    void chainIsParsedOnceForTenThousandRows() {
        ExpressionEngine.resetForTest();
        Table large = Tables.large(10_000);
        Table out = query(large, "table.Where(r -> r.Dept == \"IT\")");
        assertEquals(5_000, out.getRows().size());
        assertEquals(1, ExpressionEngine.compileCount(), "파싱이 1회를 넘었습니다");
    }

    /**
     * 숫자 컬럼에 빈 셀이 섞인 경우. {@code toNumber("")}는 null이고 strict 산술에서
     * {@code null >= 7000}은 예외다. {@code &&}의 단축 평가로 회피할 수 있어야 한다.
     */
    @Test
    void emptyNumericCellCanBeGuarded() {
        Table t = Tables.of(Arrays.asList("name", "salary"),
                new String[] {"Ann", "7000"},
                new String[] {"Bob", ""},
                new String[] {"Cho", "8000"});

        // 가드가 없으면 빈 셀에서 실패하고 행 번호를 보고한다.
        BotCommandException e = assertThrows(BotCommandException.class,
                () -> query(t, "table.Where(r -> toNumber(r.salary) >= 7000)"));
        assertTrue(e.getMessage().startsWith("1번째 연산(1번 행)"), e.getMessage());

        // 가드가 있으면 빈 셀이 toNumber에 도달하지 않는다.
        assertEquals(Arrays.asList("Ann", "Cho"), Tables.column(query(t,
                "table.Where(r -> !isEmpty(r.salary) && toNumber(r.salary) >= 7000)"), "name"));
    }

    // ---- 오류 처리 ----

    /** strict(true)는 컨텍스트 변수에만 적용된다. property 접근은 QueryRow가 검사한다. */
    @Test
    void unknownColumnIsAnErrorNotSilentZeroRows() {
        BotCommandException e = failure("table.Where(r -> r.Dpet == \"IT\")");
        assertTrue(e.getMessage().contains("Dpet"), e.getMessage());
    }

    @Test
    void nonBooleanConditionIsRejected() {
        BotCommandException e = failure("table.Where(r -> r.Years)");
        assertTrue(e.getMessage().contains("true/false"), e.getMessage());
    }

    @Test
    void errorNamesTheStepAndTheRow() {
        BotCommandException e = failure(
                "table.Where(r -> r.Dept == \"IT\").Where(r -> r.Nope == 1)");
        assertTrue(e.getMessage().startsWith("2번째 연산"), e.getMessage());
    }

    @Test
    void chainMustReturnATable() {
        BotCommandException e = failure("1 + 1");
        assertTrue(e.getMessage().contains("테이블을 반환해야"), e.getMessage());
    }

    @Test
    void syntaxErrorIsReportedBeforeTouchingAnyRow() {
        BotCommandException e = failure("table.Where(r -> r.Dept ==)");
        assertTrue(e.getMessage().contains("표현식 문법 오류"), e.getMessage());
    }

    // ---- 보안 ----

    private static void assertBlocked(String chain) {
        BotCommandException e = assertThrows(BotCommandException.class,
                () -> query(Tables.employees(), chain), "차단되지 않았습니다: " + chain);
        assertTrue(e.getMessage() != null && !e.getMessage().isEmpty());
    }

    @Test
    void getClassIsRejectedInsideLambda() {
        assertBlocked("table.Where(r -> r.getClass().getName() == \"x\")");
        assertBlocked("table.Where(r -> \"\".getClass() != null)");
    }

    @Test
    void newInstanceIsRejectedInsideLambda() {
        assertBlocked("table.Where(r -> new('java.io.File', '/tmp/x') != null)");
    }

    @Test
    void assignmentIsRejectedInsideLambda() {
        assertBlocked("table.Where(r -> r.Dept = \"IT\")");
    }

    @Test
    void arbitraryJavaObjectsAreNotReachable() {
        assertBlocked("table.Where(r -> System.exit(1) == null)");
    }
}
