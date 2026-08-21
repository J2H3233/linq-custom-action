package custompackageexample.core;

import java.util.List;

import com.automationanywhere.botcommand.data.model.Schema;
import com.automationanywhere.botcommand.data.model.table.Table;
import com.automationanywhere.botcommand.exception.BotCommandException;

/**
 * 액션 진입 시 입력 검증. 표현식을 평가하기 전에 확인할 수 있는 것만 다룬다.
 *
 * <p>컬럼명 오류는 여기서 보지 않는다. 표현식이 실제로 어떤 컬럼을 참조하는지는
 * 평가 시점에야 알 수 있고, 그 시점에 {@link QueryRow}와 {@link RowEvaluator}가
 * 사용 가능한 컬럼 목록을 붙여 예외를 던진다. 헤더 없이 읽은 테이블
 * ({@code Column1, Column2...})도 그 경로로 드러난다.
 */
public final class TableGuard {

    private TableGuard() {
    }

    /** 테이블과 스키마의 존재를 검사한다. 스키마가 없으면 컬럼명을 쓸 수 없다. */
    public static void requireTable(Table table, String label) {
        if (table == null) {
            throw new BotCommandException(label + "이(가) 비어 있습니다.");
        }
        List<Schema> schema = table.getSchema();
        if (schema == null || schema.isEmpty()) {
            throw new BotCommandException(
                    label + "에 컬럼 정보가 없습니다. 표현식에 컬럼명을 쓸 수 없습니다.");
        }
        if (table.getRows() == null) {
            throw new BotCommandException(label + "에 행 정보가 없습니다.");
        }
    }

    /** 표현식의 존재를 검사한다. 빈 문자열은 JEXL에서 null로 평가되어 오류 없이 통과한다. */
    public static void requireExpression(String source, String label) {
        if (source == null || source.trim().isEmpty()) {
            throw new BotCommandException(label + "이(가) 비어 있습니다.");
        }
    }
}
