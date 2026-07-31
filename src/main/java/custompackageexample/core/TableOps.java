package custompackageexample.core;

import java.util.ArrayList;
import java.util.List;

import com.automationanywhere.botcommand.data.model.table.Row;
import com.automationanywhere.botcommand.data.model.table.Table;

/**
 * 테이블 연산. 순수 Java라서 Control Room 없이 JUnit으로 돈다.
 *
 * <p>Stream을 쓰지 않는 이유: {@code _rowIndex} 주입과 예외 메시지의 행 번호 때문이다.
 */
public final class TableOps {

    private TableOps() {
    }

    /**
     * 조건을 만족하는 행만 남긴다.
     *
     * @param negate true면 반대로 동작한다. RemoveWhere가 이 한 글자 차이다.
     */
    public static Table filter(Table source, RowEvaluator evaluator, boolean negate) {
        List<Row> rows = source.getRows();
        List<Row> kept = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (evaluator.test(row, i) != negate) {
                kept.add(row);
            }
        }
        // 스키마 리스트만 새로 만들고 Schema 객체는 공유한다. 여기서 고치지 않으므로 안전하다.
        return new Table(new ArrayList<>(source.getSchema()), kept);
    }
}
