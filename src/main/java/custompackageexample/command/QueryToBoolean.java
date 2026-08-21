package custompackageexample.command;

import com.automationanywhere.botcommand.data.impl.BooleanValue;
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
 * 람다 체인으로 테이블을 질의해 true/false를 반환한다.
 *
 * <p>{@link QueryToTable}과 코어를 공유하며 종료 연산자만 다르다. 반환 타입이
 * 액션 선택 시점에 확정되도록 액션을 나눈다.
 */
@BotCommand
@CommandPkg(
        name = "queryToBoolean",
        label = "LinqTable: Query to Boolean",
        node_label = "Boolean from {{table}} : {{query}}",
        description = "람다 체인의 결과를 true/false로 반환합니다 (Any / All).",
        icon = "pkg.svg",
        return_label = "Result",
        return_type = DataType.BOOLEAN,
        return_required = true
)
public class QueryToBoolean {

    // 표현식 입력창 아래에 표시되는 함수 목록.
    private static final String BOOLEAN_HINT = "Where() · OrderBy() · OrderByDescending() · "
            + "ThenBy() · ThenByDescending() · Select() · Take() · Skip() · Any() · All() · "
            + Fn.FUNCTION_HELP;

    @Execute
    public BooleanValue action(
            @Idx(index = "1", type = AttributeType.TABLE)
            @Pkg(label = "Source table")
            @NotEmpty
            Table table,

            @Idx(index = "2", type = AttributeType.TEXTAREA)
            @Pkg(label = "Query", default_value_type = DataType.STRING,
                    description = "예: table.Any(r -> r.dept == \"부서1\")")
            @NotEmpty
            String query,

            @Idx(index = "3", type = AttributeType.HELP)
            @Pkg(label = "", description = BOOLEAN_HINT)
            String hint,

            @Idx(index = "4", type = AttributeType.CHECKBOX)
            @Pkg(label = "Auto-detect numeric columns",
                    description = "켜면 숫자로 보이는 셀을 숫자로 다룹니다. 앞자리 0이 있는 "
                            + "우편번호/사번은 문자열로 유지됩니다. 끄면 toNumber(...)를 쓰십시오.",
                    default_value_type = DataType.BOOLEAN, default_value = "false")
            Boolean autoDetectNumeric) {

        TableGuard.requireTable(table, "Source table");
        TableGuard.requireExpression(query, "Query");

        Queryable source = new Queryable(table, Boolean.TRUE.equals(autoDetectNumeric));
        return new BooleanValue(ExpressionEngine.evaluateBoolean(query, source));
    }
}
