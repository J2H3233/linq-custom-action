package custompackageexample.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.automationanywhere.botcommand.data.model.table.Table;
import com.automationanywhere.botcommand.exception.BotCommandException;

/** where절(filter) 동작과 보안 설정 검증. */
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

    /** 표현식은 컴파일 또는 평가 중 어디서든 막히면 된다. 통과만 안 하면 성공. */
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
        // JEXL에서 =는 대입이다. 막지 않으면 필터가 아니라 전체 행을 통과시키고 에러도 없다.
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
        // strict(true)의 존재 이유. 컬럼명 오타가 조용히 0건이 되면 원인을 못 찾는다.
        assertBlocked("Departmnt == \"IT\"");
    }

    @Test
    void nonBooleanConditionIsRejected() {
        BotCommandException e = assertThrows(BotCommandException.class,
                () -> filterNames(Tables.employees(), "Years"));
        assertTrue(e.getMessage().contains("true/false"), e.getMessage());
    }

    // ---- 헤더 누락 함정 ----

    @Test
    void headerlessTableIsRejectedWithClearMessage() {
        Table headerless = Tables.withoutHeader(4,
                new String[] {"Name", "Dept", "Years", "Salary"},
                new String[] {"Ann", "IT", "5", "6200"});
        BotCommandException e = assertThrows(BotCommandException.class,
                () -> TableGuard.requireNamedColumns(headerless));
        assertTrue(e.getMessage().contains("헤더 없이"), e.getMessage());
    }

    @Test
    void partiallyNamedColumnsArePermitted() {
        Table mixed = Tables.of(Arrays.asList("Name", "Column2"),
                new String[] {"Ann", "IT"});
        TableGuard.requireNamedColumns(mixed);
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
