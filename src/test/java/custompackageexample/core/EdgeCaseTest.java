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
 * 경계 케이스의 동작을 고정한다.
 *
 * <p>여기 적힌 값들은 "이래야 한다"가 아니라 "실제로 이렇다"를 확인한 결과다.
 * 빈 셀이나 짧은 행 같은 건 Excel에서 흔하게 들어오는데, 동작이 조용히 바뀌면
 * 봇이 틀린 결과를 내면서도 에러를 안 낸다. 그래서 값으로 못 박아 둔다.
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

    /** null 셀은 비교에서 false다. 예외가 아니다. 한 칸 비었다고 봇이 멈추면 곤란하다. */
    @Test
    void nullCellComparesAsFalse() {
        assertEquals(0, names(withNullDept(), "Dept == \"IT\"", false).size());
    }

    @Test
    void nullCellIsFoundByIsEmpty() {
        assertEquals(Arrays.asList("Ann"), names(withNullDept(), "isEmpty(Dept)", false));
    }

    /** 스키마보다 셀이 적은 행. 없는 칸은 null로 바인딩되고, 없는 컬럼 취급이 아니다. */
    @Test
    void missingCellBindsAsNull() {
        Table shortRow = new Table(
                Tables.of(Arrays.asList("Name", "Dept", "Years")).getSchema(),
                new ArrayList<>(Arrays.asList(Tables.row("Ann"))));
        assertEquals(0, names(shortRow, "Dept == \"IT\"", false).size());
        assertEquals(Arrays.asList("Ann"), names(shortRow, "isEmpty(Years)", false));
    }

    // ---- 숫자 변환 ----

    /** 숫자가 아닌 셀은 조용히 0이 되지 않는다. 어느 행에서 걸렸는지 메시지에 나와야 한다. */
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

    /** 자동 변환을 켜도 우편번호 01234의 앞 0은 살아 있어야 한다. */
    @Test
    void leadingZeroSurvivesAutoNumeric() {
        Table zips = Tables.of(Arrays.asList("Name", "Zip"),
                new String[] {"Ann", "01234"},
                new String[] {"Bob", "98765"});
        assertEquals(Arrays.asList("Ann"), names(zips, "startsWith(Zip, \"01\")", true));
        assertEquals(Arrays.asList("Bob"), names(zips, "startsWith(Zip, \"98\")", true));
    }

    // ---- 컬럼명 ----

    /** 컬럼명은 대소문자를 가린다. Dept를 dept로 쓰면 0건이 아니라 오류다. */
    @Test
    void columnNameIsCaseSensitive() {
        BotCommandException e = failureOf(Tables.employees(), "dept == \"IT\"");
        assertTrue(e.getMessage().contains("평가 실패"), e.getMessage());
    }

    /**
     * 공백이 든 컬럼명은 표현식에서 쓸 수 없다. JEXL이 식별자 두 개로 파싱한다.
     * 문법 오류로 즉시 잡히므로 봇 개발자가 원인을 본다.
     */
    @Test
    void columnNameWithSpaceCannotBeReferenced() {
        Table spaced = Tables.of(Arrays.asList("Name", "Job Title"), new String[] {"Ann", "Dev"});
        BotCommandException e = failureOf(spaced, "Job Title == \"Dev\"");
        assertTrue(e.getMessage().contains("표현식 문법 오류"), e.getMessage());
    }

    private static Table withNullDept() {
        List<Value> cells = new ArrayList<>();
        cells.add(new StringValue("Ann"));
        cells.add(new StringValue((String) null));
        return new Table(Tables.of(Arrays.asList("Name", "Dept")).getSchema(),
                new ArrayList<>(Arrays.asList(new Row(cells))));
    }
}
