package custompackageexample.command;

import com.automationanywhere.botcommand.data.impl.TableValue;
import com.automationanywhere.botcommand.data.model.table.Table;
import com.automationanywhere.commandsdk.annotations.BotCommand;
import com.automationanywhere.commandsdk.annotations.CommandPkg;
import com.automationanywhere.commandsdk.annotations.Execute;
import com.automationanywhere.commandsdk.annotations.Idx;
import com.automationanywhere.commandsdk.annotations.Pkg;
import com.automationanywhere.commandsdk.annotations.rules.NotEmpty;
import com.automationanywhere.commandsdk.model.AttributeType;
import com.automationanywhere.commandsdk.model.DataType;

import custompackageexample.core.ExpressionEngine;
import custompackageexample.core.Fn;
import custompackageexample.core.Queryable;
import custompackageexample.core.TableGuard;

/**
 * 람다 체인으로 테이블을 질의해 테이블을 반환한다. LINQ 메서드 구문에 대응한다.
 *
 * <p>{@link FilterToTable}과 달리 체인 전체를 한 액션이 받는다. 중간 결과가 테이블로
 * 실체화되지 않으므로 {@code Take} / {@code First}가 원본 읽기를 중단할 수 있다.
 *
 * <p>숫자를 반환하는 종료 연산자는 {@link QueryToNumber}, true/false를 반환하는
 * 종료 연산자는 {@link QueryToBoolean}이 담당한다.
 */
@BotCommand
@CommandPkg(
        name = "queryToTable",
        label = "LinqTable: Query to Table",
        node_label = "Query {{table}} : {{query}}",
        description = "람다 체인으로 테이블을 걸러내고 정렬하고 컬럼을 고릅니다.",
        icon = "pkg.svg",
        return_label = "Result table",
        return_type = DataType.TABLE,
        return_required = true
)
public class QueryToTable {

    // 표현식 입력창 아래에 표시되는 함수 목록.
    private static final String QUERY_HINT = "Where() · OrderBy() · OrderByDescending() · "
            + "ThenBy() · ThenByDescending() · Select() · Take() · Skip() · First() · "
            + "FirstOrDefault() · " + Fn.FUNCTION_HELP;

    @Execute
    public TableValue action(
            @Idx(index = "1", type = AttributeType.TABLE)
            @Pkg(label = "Source table")
            @NotEmpty
            Table table,

            @Idx(index = "2", type = AttributeType.TEXTAREA)
            @Pkg(label = "Query", default_value_type = DataType.STRING,
                    description = "예: table.Where(r -> r.dept == \"부서1\").OrderBy(r -> toNumber(r.age))")
            @NotEmpty
            String query,

            @Idx(index = "3", type = AttributeType.HELP)
            @Pkg(label = "", description = QUERY_HINT)
            String hint,

            @Idx(index = "4", type = AttributeType.CHECKBOX)
            @Pkg(label = "Auto-detect numeric columns",
                    description = "켜면 숫자로 보이는 셀을 숫자로 다룹니다. 앞자리 0이 있는 "
                            + "우편번호/사번은 문자열로 유지됩니다. 끄면 toNumber(...)를 쓰십시오.",
                    default_value_type = DataType.BOOLEAN, default_value = "false")
            Boolean autoDetectNumeric) {

        TableGuard.requireTable(table, "Source table");
        TableGuard.requireExpression(query, "Query");

        // 파싱은 1회. 체인의 각 연산이 그 결과를 공유한다.
        Queryable source = new Queryable(table, Boolean.TRUE.equals(autoDetectNumeric));
        Queryable result = ExpressionEngine.evaluateQuery(query, source);

        return new TableValue(result.toTable());
    }
}
