package custompackageexample.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.data.model.table.Row;
import com.automationanywhere.botcommand.data.model.table.Table;
import com.automationanywhere.botcommand.exception.BotCommandException;

/**
 * 경계 조건의 동작을 고정한다.
 *
 * <p>빈 셀과 스키마보다 짧은 행은 Excel 입력에서 흔하다. 이 동작이 바뀌면 오류 없이
 * 결과만 달라지므로 값으로 고정한다.
 */
class EdgeCaseTest {

    private static List<String> names(Table table, String condition, boolean autoNumeric) {
        RowEvaluator evaluator = new RowEvaluator(condition, table.getSchema(), autoNumeric);
        return Tables.column(TableOps.filter(table, evaluator, false), "Name");
    }

    private static BotCommandException failureOf(Table table, String condition) {
        return assertThrows(BotCommandException.class, () -> names(table, condition, false));
    }

    // ---- 빈 값 ----

    @Test
    void emptyTableGivesEmptyResultNotAnError() {
        Table empty = Tables.of(Arrays.asList("Name", "Dept"));
        assertEquals(0, names(empty, "Dept == \"IT\"", false).size());
    }

    /** null 셀은 비교에서 false로 평가된다. 예외가 아니다. */
    @Test
    void nullCellComparesAsFalse() {
        assertEquals(0, names(withNullDept(), "Dept == \"IT\"", false).size());
    }

    @Test
    void nullCellIsFoundByIsEmpty() {
        assertEquals(Arrays.asList("Ann"), names(withNullDept(), "isEmpty(Dept)", false));
    }

    /** 스키마보다 셀이 적은 행. 없는 칸은 null로 바인딩된다. 미정의 컬럼과는 다르다. */
    @Test
    void missingCellBindsAsNull() {
        Table shortRow = new Table(
                Tables.of(Arrays.asList("Name", "Dept", "Years")).getSchema(),
                new ArrayList<>(Arrays.asList(Tables.row("Ann"))));
        assertEquals(0, names(shortRow, "Dept == \"IT\"", false).size());
        assertEquals(Arrays.asList("Ann"), names(shortRow, "isEmpty(Years)", false));
    }

    // ---- 숫자 변환 ----

    /** 숫자가 아닌 셀은 0으로 대체되지 않는다. 메시지에 행 번호를 포함한다. */
    @Test
    void toNumberFailurePointsAtTheOffendingRow() {
        Table dirty = Tables.of(Arrays.asList("Name", "Salary"),
                new String[] {"Ann", "6200"},
                new String[] {"Bob", "N/A"},
                new String[] {"Cho", ""});
        BotCommandException e = failureOf(dirty, "toNumber(Salary) > 5000");
        assertTrue(e.getMessage().startsWith("1번 행"), e.getMessage());
    }

    @Test
    void thousandSeparatorIsAccepted() {
        Table commas = Tables.of(Arrays.asList("Name", "Salary"), new String[] {"Ann", "1,200"});
        assertEquals(Arrays.asList("Ann"), names(commas, "toNumber(Salary) > 1000", false));
    }

    /** 자동 변환을 켜도 우편번호 01234의 앞자리 0은 유지된다. */
    @Test
    void leadingZeroSurvivesAutoNumeric() {
        Table zips = Tables.of(Arrays.asList("Name", "Zip"),
                new String[] {"Ann", "01234"},
                new String[] {"Bob", "98765"});
        assertEquals(Arrays.asList("Ann"), names(zips, "startsWith(Zip, \"01\")", true));
        assertEquals(Arrays.asList("Bob"), names(zips, "startsWith(Zip, \"98\")", true));
    }

    // ---- 컬럼명 ----

    /** 컬럼명은 대소문자를 구분한다. 불일치는 0건이 아니라 오류다. */
    @Test
    void columnNameIsCaseSensitive() {
        BotCommandException e = failureOf(Tables.employees(), "dept == \"IT\"");
        assertTrue(e.getMessage().contains("컬럼 'dept'"), e.getMessage());
        assertTrue(e.getMessage().contains("Dept"), e.getMessage());
    }

    /** 공백이 포함된 컬럼명은 표현식에서 쓸 수 없다. JEXL이 식별자 두 개로 파싱한다. */
    @Test
    void columnNameWithSpaceCannotBeReferenced() {
        Table spaced = Tables.of(Arrays.asList("Name", "Job Title"), new String[] {"Ann", "Dev"});
        BotCommandException e = failureOf(spaced, "Job Title == \"Dev\"");
        assertTrue(e.getMessage().contains("표현식 문법 오류"), e.getMessage());
    }

    // SDK의 Row 생성자가 raw List<Value>를 받는다. Tables.row()와 같은 제약.
    @SuppressWarnings("rawtypes")
    private static Table withNullDept() {
        List<Value> cells = new ArrayList<>();
        cells.add(new StringValue("Ann"));
        cells.add(new StringValue((String) null));
        return new Table(Tables.of(Arrays.asList("Name", "Dept")).getSchema(),
                new ArrayList<>(Arrays.asList(new Row(cells))));
    }
}
