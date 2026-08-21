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

import custompackageexample.core.Fn;
import custompackageexample.core.RowEvaluator;
import custompackageexample.core.TableGuard;
import custompackageexample.core.TableOps;

/**
 * 조건식 하나로 테이블 행을 걸러낸다. LINQ {@code Where}에 대응한다.
 *
 * <p>컬럼명을 자유 변수로 참조한다. 람다와 체이닝은 {@link QueryToTable}이 담당한다.
 *
 * <p>core를 호출하는 어댑터다. 상속을 쓰지 않는 것은 SDK가 리플렉션으로
 * {@code @Execute}와 파라미터 어노테이션을 스캔하는데, 상속 계층 탐색 동작이
 * SDK 버전마다 다르기 때문이다. 
 */
@BotCommand
@CommandPkg(
        name = "filterToTable",
        label = "LinqTable: Filter to Table",
        node_label = "Filter {{table}} where {{condition}}",
        description = "조건식을 만족하는 행만 남긴 새 테이블을 반환합니다.",
        icon = "pkg.svg",
        return_label = "Filtered table",
        return_type = DataType.TABLE,
        return_required = true
)
public class FilterToTable {

    // 표현식 입력창 아래에 표시되는 함수 목록.
    static final String CONDITION_HINT = Fn.FUNCTION_HELP;

    @Execute
    public TableValue action(
            @Idx(index = "1", type = AttributeType.TABLE)
            @Pkg(label = "Source table")
            @NotEmpty
            Table table,

            @Idx(index = "2", type = AttributeType.TEXTAREA)
            @Pkg(label = "Condition", default_value_type = DataType.STRING,
                    description = "예: Dept == \"IT\" && toNumber(Years) > 3")
            @NotEmpty
            String condition,

            @Idx(index = "3", type = AttributeType.HELP)
            @Pkg(label = "", description = FilterToTable.CONDITION_HINT)
            String hint,

            @Idx(index = "4", type = AttributeType.CHECKBOX)
            @Pkg(label = "Auto-detect numeric columns",
                    description = "켜면 숫자로 보이는 셀을 숫자로 다룹니다. 앞자리 0이 있는 "
                            + "우편번호/사번은 문자열로 유지됩니다. 끄면 toNumber(...)를 쓰십시오.",
                    default_value_type = DataType.BOOLEAN, default_value = "false")
            Boolean autoDetectNumeric) {

        TableGuard.requireTable(table, "Source table");
        TableGuard.requireExpression(condition, "Condition");

        // 파싱은 1회. 행 루프 밖이다.
        RowEvaluator evaluator = new RowEvaluator(
                condition, table.getSchema(), Boolean.TRUE.equals(autoDetectNumeric));

        Table result = TableOps.filter(table, evaluator, false);
        return new TableValue(result);
    }
}
