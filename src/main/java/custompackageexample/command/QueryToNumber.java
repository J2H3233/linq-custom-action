package custompackageexample.command;

import com.automationanywhere.botcommand.data.impl.NumberValue;
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
 * 람다 체인으로 테이블을 질의해 숫자를 반환한다.
 *
 * <p>{@link QueryToTable}과 코어를 공유하며 종료 연산자만 다르다. 반환 타입이
 * 액션 선택 시점에 확정되도록 액션을 나눈다.
 */
@BotCommand
@CommandPkg(
        name = "linqTableNumber",
        label = "LinqTable: Number",
        node_label = "Number from {{table}} : {{query}}",
        description = "람다 체인의 결과를 숫자로 반환합니다 (Count / Sum / Average / Min / Max).",
        icon = "pkg.svg",
        return_label = "Result",
        return_type = DataType.NUMBER,
        return_required = true
)
public class QueryToNumber {

    /**
     * 표현식 입력창 아래에 표시되는 안내문.
     *
     * <p>줄바꿈을 넣지 않는다. HELP 설명에 {@code \n}이 포함되면 Control Room이 뒤따르는
     * 속성을 렌더링하지 않는다. 같은 이유로 HELP 속성도 하나만 둔다. 구분자는 {@code |}.
     */
    private static final String NUMBER_HINT =
            "table 로 시작하는 체인을 쓰고 숫자를 내는 연산자로 끝냅니다. "
                    + "예: table.Where(r -> r.dept == \"부서1\").Count()"
                    + "  |  종료 연산자: Count() · Sum(람다) · Average(람다) · Min(람다) · Max(람다). "
                    + "예: table.Sum(r -> toNumber(r.salary)). "
                    + "Sum 은 대상 행이 없으면 0, Average / Min / Max 는 오류입니다."
                    + "  |  중간 연산자: Where(람다) · OrderBy(람다) · OrderByDescending(람다) · "
                    + "Select([컬럼명 목록], 람다) · Take(개수) · Skip(개수). "
                    + "행 번호는 r._rowIndex 입니다(0부터)."
                    + "  |  " + Fn.FUNCTION_HELP
                    + "  |  " + TableGuard.HEADER_HINT;

    @Execute
    public NumberValue action(
            @Idx(index = "1", type = AttributeType.TABLE)
            @Pkg(label = "Source table")
            @NotEmpty
            Table table,

            @Idx(index = "2", type = AttributeType.TEXTAREA)
            @Pkg(label = "Query", default_value_type = DataType.STRING,
                    description = "예: table.Where(r -> r.dept == \"부서1\").Count()")
            @NotEmpty
            String query,

            @Idx(index = "3", type = AttributeType.HELP)
            @Pkg(label = "", description = NUMBER_HINT)
            String hint,

            @Idx(index = "4", type = AttributeType.CHECKBOX)
            @Pkg(label = "Auto-detect numeric columns",
                    description = "켜면 숫자로 보이는 셀을 숫자로 다룹니다. 앞자리 0이 있는 "
                            + "우편번호/사번은 문자열로 유지됩니다. 끄면 toNumber(...)를 쓰십시오.",
                    default_value_type = DataType.BOOLEAN, default_value = "false")
            Boolean autoDetectNumeric) {

        TableGuard.requireTable(table, "Source table");
        TableGuard.requireNamedColumns(table);
        TableGuard.requireExpression(query, "Query");

        Queryable source = new Queryable(table, Boolean.TRUE.equals(autoDetectNumeric));
        return new NumberValue(ExpressionEngine.evaluateNumber(query, source));
    }
}
