package custompackageexample.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.automationanywhere.botcommand.data.model.table.Table;
import com.automationanywhere.botcommand.exception.BotCommandException;

/** 자유 변수 방식 필터의 동작과 보안 설정 검증. 람다 방식은 {@code QueryTest}가 다룬다. */
class FilterTest {

    private static List<String> filterNames(Table table, String condition) {
        return filterNames(table, condition, false);
    }

    private static List<String> filterNames(Table table, String condition, boolean autoNumeric) {
        RowEvaluator evaluator = new RowEvaluator(condition, table.getSchema(), autoNumeric);
        return Tables.column(TableOps.filter(table, evaluator, false), "Name");
    }

    // ---- 기본 동작 ----

    @Test
    void filtersByStringEquality() {
        assertEquals(Arrays.asList("Ann", "Cho", "Dan"),
                filterNames(Tables.employees(), "Dept == \"IT\""));
    }

    @Test
    void combinesConditionsWithExplicitToNumber() {
        assertEquals(Arrays.asList("Ann", "Dan"),
                filterNames(Tables.employees(), "Dept == \"IT\" && toNumber(Years) > 3"));
    }

    @Test
    void autoDetectNumericMakesBareComparisonWork() {
        assertEquals(Arrays.asList("Ann", "Dan"),
                filterNames(Tables.employees(), "Dept == \"IT\" && Years > 3", true));
    }

    @Test
    void rowIndexIsAvailableBecauseRowsHaveNoNames() {
        assertEquals(Arrays.asList("Ann", "Bob"),
                filterNames(Tables.employees(), "_rowIndex < 2"));
    }

    @Test
    void whitelistedFunctionsAreCallable() {
        assertEquals(Arrays.asList("Bob"),
                filterNames(Tables.employees(), "eqIgnoreCase(Dept, \"hr\")"));
        assertEquals(Arrays.asList("Eve"),
                filterNames(Tables.employees(), "startsWith(Dept, \"Sal\")"));
        // 대소문자를 구분한다. "Ann" 은 "an" 을 포함하지 않는다.
        assertEquals(Arrays.asList("Dan"),
                filterNames(Tables.employees(), "contains(Name, \"an\")"));
    }

    @Test
    void negateGivesRemoveWhereForFree() {
        RowEvaluator evaluator =
                new RowEvaluator("Dept == \"IT\"", Tables.employees().getSchema(), false);
        Table kept = TableOps.filter(Tables.employees(), evaluator, true);
        assertEquals(Arrays.asList("Bob", "Eve"), Tables.column(kept, "Name"));
    }

    @Test
    void schemaIsPreservedInResult() {
        Table result = TableOps.filter(Tables.employees(),
                new RowEvaluator("Dept == \"IT\"", Tables.employees().getSchema(), false), false);
        assertEquals(4, result.getSchema().size());
        assertEquals("Salary", result.getSchema().get(3).getName());
    }

    // ---- 보안 ----

    /** 컴파일과 평가 중 어느 단계에서 차단되는지는 구분하지 않는다. */
    private static void assertBlocked(String condition) {
        BotCommandException e = assertThrows(BotCommandException.class, () -> {
            Table table = Tables.employees();
            RowEvaluator evaluator = new RowEvaluator(condition, table.getSchema(), false);
            TableOps.filter(table, evaluator, false);
        }, "차단되지 않았습니다: " + condition);
        assertTrue(e.getMessage() != null && !e.getMessage().isEmpty());
    }

    @Test
    void assignmentIsRejected() {
        // JEXL에서 =는 대입이다. 차단하지 않으면 오류 없이 전체 행이 통과한다.
        assertBlocked("Dept = \"IT\"");
    }

    @Test
    void getClassIsRejected() {
        assertBlocked("''.getClass()");
        assertBlocked("Dept.getClass().getName() == \"x\"");
    }

    @Test
    void newInstanceIsRejected() {
        assertBlocked("new('java.io.File', '/tmp/x') != null");
        assertBlocked("new java.io.File(\"/tmp/x\") != null");
    }

    @Test
    void syntaxErrorIsReportedBeforeTouchingAnyRow() {
        BotCommandException e = assertThrows(BotCommandException.class,
                () -> new RowEvaluator("Dept == \"IT\" &&", Tables.employees().getSchema(), false));
        assertTrue(e.getMessage().contains("표현식 문법 오류"), e.getMessage());
    }

    @Test
    void unknownColumnIsAnErrorNotSilentZeroRows() {
        // strict(true) 검증. 미정의 변수는 예외다.
        assertBlocked("Departmnt == \"IT\"");
    }

    /** JEXL의 "variable 'X' is undefined"를 컬럼 목록이 붙은 문구로 바꾼다. */
    @Test
    void unknownColumnMessageNamesTheColumnAndListsTheAvailableOnes() {
        BotCommandException e = assertThrows(BotCommandException.class,
                () -> filterNames(Tables.employees(), "Departmnt == \"IT\""));
        assertTrue(e.getMessage().contains("컬럼 'Departmnt'"), e.getMessage());
        assertTrue(e.getMessage().contains("Name, Dept, Years, Salary"), e.getMessage());
        // 파서 좌표 같은 JEXL 내부 정보가 새어나오지 않는다.
        assertFalse(e.getMessage().contains("ExpressionEngine"), e.getMessage());
    }

    @Test
    void nonBooleanConditionIsRejected() {
        BotCommandException e = assertThrows(BotCommandException.class,
                () -> filterNames(Tables.employees(), "Years"));
        assertTrue(e.getMessage().contains("true/false"), e.getMessage());
    }

    // ---- 헤더 누락 함정 ----

    private static Table headerless() {
        return Tables.withoutHeader(4,
                new String[] {"Name", "Dept", "Years", "Salary"},
                new String[] {"Ann", "IT", "5", "6200"});
    }

    /**
     * 헤더 없이 읽은 테이블은 별도 가드 없이 컬럼 목록으로 드러난다.
     * 메시지의 {@code Column1, Column2...}가 헤더가 안 읽혔다는 증거다.
     */
    @Test
    void headerlessTableSurfacesThroughTheColumnList() {
        BotCommandException e = assertThrows(BotCommandException.class,
                () -> filterNames(headerless(), "Dept == \"IT\""));
        assertTrue(e.getMessage().contains("Column1, Column2, Column3, Column4"), e.getMessage());
    }

    /** 자동 생성된 컬럼명도 정상적인 이름이다. 위치로 다루려는 사용을 막지 않는다. */
    @Test
    void autoGeneratedColumnNamesAreUsable() {
        assertEquals(Arrays.asList("Ann"),
                Tables.column(TableOps.filter(headerless(),
                        new RowEvaluator("Column2 == \"IT\"", headerless().getSchema(), false),
                        false), "Column1"));
    }

    /** 컬럼이 일부만 자동 생성 이름인 테이블도 그대로 쓸 수 있다. */
    @Test
    void partiallyNamedColumnsArePermitted() {
        Table mixed = Tables.of(Arrays.asList("Name", "Column2"),
                new String[] {"Ann", "IT"});
        assertEquals(Arrays.asList("Ann"),
                Tables.column(TableOps.filter(mixed,
                        new RowEvaluator("Column2 == \"IT\"", mixed.getSchema(), false),
                        false), "Name"));
    }

    // ---- 성능 계약 ----

    @Test
    void expressionIsParsedOnceForTenThousandRows() {
        ExpressionEngine.resetForTest();
        Table large = Tables.large(10_000);
        RowEvaluator evaluator = new RowEvaluator("Dept == \"IT\"", large.getSchema(), false);
        Table result = TableOps.filter(large, evaluator, false);

        assertEquals(5_000, result.getRows().size());
        assertEquals(1, ExpressionEngine.compileCount(), "파싱이 1회를 넘었습니다");
        assertEquals(10_000, evaluator.evaluationCount());
    }
}
