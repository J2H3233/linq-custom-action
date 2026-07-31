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

    @Test
    void toNumberOrKeepsRealNumbers() {
        assertEquals(Arrays.asList("Cho"),
                query("table.Where(r -> toNumberOr(r.salary, 0) > 7000)"));
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

    /**
     * {@link Fn#FUNCTION_HELP}는 어노테이션 값이라 상수여야 하므로 실제 메서드 목록과
     * 자동으로 동기화되지 않는다. 양방향으로 검사한다.
     */
    @Test
    void functionHelpMatchesTheActualWhitelist() {
        for (Method m : Fn.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || m.isSynthetic()) {
                continue;
            }
            assertTrue(Fn.FUNCTION_HELP.contains(m.getName() + "("),
                    "UI 도움말에 " + m.getName() + " 이(가) 빠져 있습니다: " + Fn.FUNCTION_HELP);
        }
    }

    @Test
    void functionHelpDoesNotAdvertiseMissingFunctions() {
        for (String advertised : new String[] {"toNumber", "toNumberOr", "isEmpty",
                "eqIgnoreCase", "contains", "startsWith"}) {
            boolean exists = false;
            for (Method m : Fn.class.getDeclaredMethods()) {
                if (m.getName().equals(advertised) && Modifier.isPublic(m.getModifiers())) {
                    exists = true;
                    break;
                }
            }
            assertTrue(exists, "UI 도움말이 없는 함수를 안내하고 있습니다: " + advertised);
        }
    }

    /** HELP 설명에 줄바꿈이 포함되면 Control Room이 뒤따르는 속성을 렌더링하지 않는다. */
    @Test
    void helpTextsHaveNoNewlines() {
        assertTrue(Fn.FUNCTION_HELP.indexOf('\n') < 0, Fn.FUNCTION_HELP);
        assertTrue(TableGuard.HEADER_HINT.indexOf('\n') < 0, TableGuard.HEADER_HINT);
    }
}
