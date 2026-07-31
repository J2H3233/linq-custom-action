package custompackageexample.core;

import java.util.List;
import java.util.regex.Pattern;

import com.automationanywhere.botcommand.data.model.Schema;
import com.automationanywhere.botcommand.data.model.table.Table;
import com.automationanywhere.botcommand.exception.BotCommandException;

/**
 * 액션 진입 시 입력 검증.
 *
 * <p>주된 대상은 헤더 누락이다. Excel/CSV를 "Contains header" 없이 읽으면 컬럼명이
 * {@code Column1, Column2...}로 생성되고 헤더 텍스트는 0번 행 데이터가 된다.
 * 이 상태에서는 표현식에 컬럼명을 쓸 수 없고, 검사하지 않으면 오류 없이 0건이 반환된다.
 */
public final class TableGuard {

    /** 헤더 없이 읽은 테이블에 A360이 부여하는 자동 컬럼명 패턴. */
    private static final Pattern AUTO_COLUMN = Pattern.compile("Column\\d+");

    /** 액션 UI의 표현식 입력창 아래에 표시되는 안내문. */
    public static final String HEADER_HINT =
            "Excel/CSV 읽기에서 'Contains header'를 켜지 않으면 컬럼명이 Column1, Column2... "
                    + "로 생성되어 컬럼명을 쓸 수 없습니다.";

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

    /**
     * 컬럼명이 모두 자동 생성 이름이면 거부한다.
     *
     * <p>일부만 {@code Column\d+}인 경우는 통과시킨다. 이름이 있는 컬럼으로 표현식을
     * 작성할 수 있기 때문이다.
     */
    public static void requireNamedColumns(Table table) {
        List<Schema> schema = table.getSchema();
        for (Schema column : schema) {
            String name = column.getName();
            if (name != null && !AUTO_COLUMN.matcher(name).matches()) {
                return;
            }
        }
        throw new BotCommandException(
                "헤더 없이 읽은 테이블로 보입니다. 컬럼명이 Column1... 형태라 표현식에 컬럼명을 쓸 수 없습니다. "
                        + "테이블을 읽는 액션에서 'Contains header' 옵션을 켜십시오.");
    }

    /** 표현식의 존재를 검사한다. 빈 문자열은 JEXL에서 null로 평가되어 오류 없이 통과한다. */
    public static void requireExpression(String source, String label) {
        if (source == null || source.trim().isEmpty()) {
            throw new BotCommandException(label + "이(가) 비어 있습니다.");
        }
    }
}
