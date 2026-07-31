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
 * 람다 체인으로 테이블을 질의하는 액션. LINQ 메서드 구문에 대응한다.
 *
 * <p>{@link FilterTable}과 달리 체인 전체를 한 액션이 받는다. 중간 결과가 테이블로
 * 실체화되지 않으므로 {@code Take} / {@code First} 같은 조기 종료 연산을 추가할 수 있다.
 */
@BotCommand
@CommandPkg(
        name = "linqTableQuery",
        label = "LinqTable: Query",
        node_label = "Query {{table}} : {{query}}",
        description = "람다 체인으로 테이블을 걸러내고 정렬하고 컬럼을 고릅니다.",
        icon = "pkg.svg",
        return_label = "Result table",
        return_type = DataType.TABLE,
        return_required = true
)
public class QueryTable {

    /**
     * 표현식 입력창 아래에 표시되는 안내문.
     *
     * <p>줄바꿈을 넣지 않는다. HELP 설명에 {@code \n}이 포함되면 Control Room이 뒤따르는
     * 속성을 렌더링하지 않는다. 같은 이유로 HELP 속성도 하나만 둔다. 구분자는 {@code |}.
     */
    private static final String QUERY_HINT =
            "table 로 시작하는 체인을 씁니다. 컬럼은 r.컬럼명 으로 참조하고 대소문자를 가립니다. "
                    + "예: table.Where(r -> r.dept == \"부서1\" && toNumber(r.age) > 30)"
                    + ".OrderByDescending(r -> toNumber(r.age))"
                    + ".Select([\"name\", \"age\"], r -> [r.name, toNumber(r.age)])"
                    + "  |  연산자: Where(람다) · OrderBy(람다) · OrderByDescending(람다) · "
                    + "Select([컬럼명 목록], 람다). 행 번호는 r._rowIndex 입니다(0부터). "
                    + "Select 가 컬럼명을 따로 받는 것은 컬럼 순서가 테이블에서 의미를 갖기 때문입니다."
                    + "  |  " + Fn.FUNCTION_HELP
                    + "  |  " + TableGuard.HEADER_HINT;

    @Execute
    public TableValue action(
            @Idx(index = "1", type = AttributeType.TABLE)
            @Pkg(label = "Source table")
            @NotEmpty
            Table table,

            @Idx(index = "2", type = AttributeType.TEXTAREA)
            @Pkg(label = "Query", default_value_type = DataType.STRING,
                    description = "예: table.Where(r -> r.Dept == \"IT\").OrderBy(r -> toNumber(r.Age))")
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
        TableGuard.requireNamedColumns(table);
        TableGuard.requireExpression(query, "Query");

        // 파싱은 1회. 체인의 각 연산이 그 결과를 공유한다.
        Queryable source = new Queryable(table, Boolean.TRUE.equals(autoDetectNumeric));
        Queryable result = ExpressionEngine.evaluateQuery(query, source);

        return new TableValue(result.toTable());
    }
}
