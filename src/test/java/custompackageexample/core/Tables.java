package custompackageexample.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.data.model.Schema;
import com.automationanywhere.botcommand.data.model.table.Row;
import com.automationanywhere.botcommand.data.model.table.Table;
import com.automationanywhere.botcore.api.dto.AttributeType;

/**
 * 테스트용 가짜 테이블. Table/Row/Schema는 SDK 클래스지만 Control Room 없이 new로 만들 수 있다.
 *
 * <p>값은 전부 StringValue다. Excel "Get multiple cells"가 실제로 그렇게 주기 때문이고,
 * 그래야 타입 강제 변환 동작을 제대로 검증한다.
 */
final class Tables {

    private Tables() {
    }

    /** 컬럼명이 있는 정상 테이블. */
    static Table of(List<String> columnNames, String[]... rows) {
        List<Schema> schema = new ArrayList<>();
        for (String name : columnNames) {
            schema.add(new Schema(name, AttributeType.STRING));
        }
        List<Row> rowList = new ArrayList<>();
        for (String[] cells : rows) {
            rowList.add(row(cells));
        }
        return new Table(schema, rowList);
    }

    /** 헤더 없이 읽은 테이블. 컬럼명이 Column1, Column2... 로 자동 생성된 상태. */
    static Table withoutHeader(int columnCount, String[]... rows) {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            names.add("Column" + i);
        }
        return of(names, rows);
    }

    static Row row(String... cells) {
        List<Value> values = new ArrayList<>();
        for (String cell : cells) {
            values.add(new StringValue(cell));
        }
        return new Row(values);
    }

    /** 대부분의 테스트가 쓰는 기본 직원 테이블. */
    static Table employees() {
        return of(Arrays.asList("Name", "Dept", "Years", "Salary"),
                new String[] {"Ann", "IT", "5", "6200"},
                new String[] {"Bob", "HR", "2", "4100"},
                new String[] {"Cho", "IT", "1", "3900"},
                new String[] {"Dan", "IT", "8", "7300"},
                new String[] {"Eve", "Sales", "4", "5000"});
    }

    /** 특정 컬럼의 값들을 순서대로 뽑는다. 결과 검증용. */
    static List<String> column(Table table, String columnName) {
        int index = -1;
        for (int i = 0; i < table.getSchema().size(); i++) {
            if (columnName.equals(table.getSchema().get(i).getName())) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new IllegalArgumentException("no such column: " + columnName);
        }
        List<String> out = new ArrayList<>();
        for (Row row : table.getRows()) {
            out.add(String.valueOf(row.getValues().get(index).get()));
        }
        return out;
    }

    /** 성능 검증용 큰 테이블. */
    static Table large(int rowCount) {
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            rows.add(new String[] {"emp" + i, i % 2 == 0 ? "IT" : "HR", String.valueOf(i % 40),
                    String.valueOf(3000 + (i * 7) % 5000)});
        }
        return of(Arrays.asList("Name", "Dept", "Years", "Salary"), rows.toArray(new String[0][]));
    }
}
