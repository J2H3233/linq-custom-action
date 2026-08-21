package custompackageexample.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.automationanywhere.botcommand.data.model.table.Table;
import com.automationanywhere.botcommand.exception.BotCommandException;

/** 화이트리스트 함수와 UI 안내 문자열의 검증. */
class FnTest {

    private static Table salaries() {
        return Tables.of(Arrays.asList("name", "salary"),
                new String[] {"Ann", "7000"},
                new String[] {"Bob", ""},
                new String[] {"Cho", "8000"},
                new String[] {"Dan", "N/A"});
    }

    private static java.util.List<String> query(String chain) {
        return Tables.column(
                ExpressionEngine.evaluateQuery(chain, new Queryable(salaries(), false)).toTable(),
                "name");
    }

    // ---- toNumberOr ----

    @Test
    void toNumberOrFallsBackOnEmptyAndGarbage() {
        assertEquals(Arrays.asList("Ann", "Cho"),
                query("table.Where(r -> toNumberOr(r.salary, 0) >= 7000)"));
    }

    /** 기본값이 실제로 적용되는지. 0 이 아닌 값을 주면 빈 셀과 N/A 행이 통과한다. */
    @Test
    void fallbackValueIsApplied() {
        assertEquals(Arrays.asList("Ann", "Bob", "Cho", "Dan"),
                query("table.Where(r -> toNumberOr(r.salary, 9999) >= 7000)"));
    }

    /** 기본값이 숫자가 아니면 오류로 처리한다. */
    @Test
    void toNumberOrRejectsNonNumericFallback() {
        BotCommandException e = assertThrows(BotCommandException.class,
                () -> query("table.Where(r -> toNumberOr(r.salary, \"없음\") >= 7000)"));
        assertTrue(e.getMessage().contains("기본값이 숫자가 아닙니다"), e.getMessage());
    }

    /** toNumberOr 추가가 toNumber의 동작을 바꾸지 않는다. */
    @Test
    void plainToNumberStillFailsOnEmptyCell() {
        BotCommandException e = assertThrows(BotCommandException.class,
                () -> query("table.Where(r -> toNumber(r.salary) >= 7000)"));
        assertTrue(e.getMessage().startsWith("1번째 연산(1번 행)"), e.getMessage());
    }

    // ---- UI 도움말이 실제 함수 목록과 어긋나지 않게 ----

    /** {@link Fn#FUNCTION_HELP}와 실제 public 메서드 목록의 일치 검사. */
    @Test
    void functionHelpListsEveryFunction() {
        for (Method m : Fn.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || m.isSynthetic()) {
                continue;
            }
            assertTrue(Fn.FUNCTION_HELP.contains(m.getName() + "("),
                    "UI 도움말에 " + m.getName() + " 이(가) 빠져 있습니다: " + Fn.FUNCTION_HELP);
        }
    }

    /** HELP 설명에 줄바꿈이 포함되면 Control Room이 뒤따르는 속성을 렌더링하지 않는다. */
    @Test
    void helpTextsHaveNoNewlines() {
        assertTrue(Fn.FUNCTION_HELP.indexOf('\n') < 0, Fn.FUNCTION_HELP);
    }
}
