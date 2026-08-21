# LinqTable

.NET LINQ의 질의 방식을 Automation Anywhere A360 테이블에 옮긴 커스텀 패키지입니다.
표현식 하나로 행을 걸러내고, 정렬하고, 컬럼을 고르고, 집계합니다.

원본 테이블은 어떤 액션에서도 변경되지 않습니다.

---

## 1. 액션 구성

액션은 4개이고, 이름은 `입력 언어 → 출력 타입` 규칙을 따릅니다.


| 액션                          | 입력 언어   | 반환 타입 | 역할    |
| ----------------------------- | ----------- | --------- | ------- |
| `LinqTable: Filter to Table`  | 조건식 하나 | TABLE     | 검증용  |
| `LinqTable: Query to Table`   | 람다 체인   | TABLE     | 본 기능 |
| `LinqTable: Query to Number`  | 람다 체인   | NUMBER    | 본 기능 |
| `LinqTable: Query to Boolean` | 람다 체인   | BOOLEAN   | 본 기능 |

네 액션 모두 입력은 `Source table` / `Query`(또는 `Condition`) / `Auto-detect numeric columns`로
동일합니다.

### Filter to Table — 검증용

체인 없이 조건식 하나만 받습니다. 컬럼명을 `r.` 없이 자유 변수로 씁니다.

```
Dept == "IT" && toNumber(Years) > 3
_rowIndex < 100
startsWith(Zip, "01")
```

가장 먼저 만든 액션이고, 지금은 표현식 평가·컬럼 바인딩·숫자 변환이 제대로 도는지
확인하는 기준점으로 남겨둔 것입니다. 체인이 필요 없는 단순 필터에는 그대로 써도 됩니다.
실제 기능은 아래 세 액션이 담당합니다.

구현도 나머지 셋과 갈라집니다. `RowEvaluator` + `TableOps.filter`를 쓰며,
행마다 컬럼명을 JEXL 컨텍스트 변수로 직접 바인딩합니다.

### Query to Table / Number / Boolean — 왜 3개로 나눴나

세 액션은 **코어가 완전히 같습니다.** 파서도, 체인 조립도, 실행 루프도 하나입니다.
차이는 마지막에 무엇을 반환하느냐뿐입니다.

그런데도 액션을 나눈 이유는 A360의 변수 타입 검사 때문입니다.
A360은 액션의 반환 타입을 `@CommandPkg(return_type = ...)`에서 **컴파일 시점에** 읽습니다.
런타임에 "이 표현식은 숫자를 반환하니까 NUMBER"라고 정할 수단이 없습니다.

액션이 하나뿐이라면 반환 타입을 뭉개야 하고, 그러면 Bot Editor가 변수 타입을 검사하지 못해
오류가 봇 실행 시점으로 밀립니다. 액션을 셋으로 나누면 **액션을 고르는 순간 반환 타입이 확정**되므로,
Bot Editor가 대입 대상 변수의 타입을 그 자리에서 검사할 수 있습니다.


| 액션             | 허용되는 종료 연산자                                                      |
| ---------------- | ------------------------------------------------------------------------- |
| Query to Table   | 없음(체인 그대로) ·`First()` · `FirstOrDefault()`                       |
| Query to Number  | `Count()` · `Sum(람다)` · `Average(람다)` · `Min(람다)` · `Max(람다)` |
| Query to Boolean | `Any()` · `Any(람다)` · `All(람다)`                                     |

액션과 종료 연산자가 어긋나면 결과 타입을 검사해서 오류로 알려줍니다.
`Query to Number`에 `table.Where(...)`를 넣으면
`쿼리는 숫자를 반환해야 합니다. 실제: Queryable. Count / Sum / Average / Min / Max 로 끝내십시오.`
가 나옵니다.

세 액션의 커맨드 클래스는 상속을 쓰지 않고 각각 독립적으로 작성돼 있습니다.
SDK가 리플렉션으로 `@Execute`와 파라미터 어노테이션을 스캔하는데,
상속 계층을 어디까지 탐색하는지가 SDK 버전마다 달라 안전하지 않기 때문입니다.
각 클래스는 검증 3줄 + 코어 호출 2줄짜리 얇은 어댑터입니다.

---

## 2. Query 액션 3종의 공통 동작

액션 클래스는 아래 순서를 그대로 실행합니다. 세 액션 모두 (9)단계만 다릅니다.

### (1) 입력 검증 — `TableGuard`

```java
TableGuard.requireTable(table, "Source table");
TableGuard.requireExpression(query, "Query");
```

**여기서는 표현식을 보기 전에 확정할 수 있는 것만 검사합니다.**

- 스키마나 행 정보가 아예 없으면 표현식을 평가할 수 없습니다.
- 빈 표현식은 JEXL에서 `null`로 평가되어 아무 오류 없이 통과합니다. 그래서 별도로 검사합니다.

**컬럼명은 여기서 보지 않습니다.** 표현식이 어떤 컬럼을 참조하는지는 평가 시점에야
알 수 있고, 그때 `QueryRow` / `RowEvaluator`가 사용 가능한 컬럼 목록을 붙여 예외를 던집니다.

Excel/CSV를 `Contains header` 없이 읽어 컬럼명이 `Column1, Column2...`가 된 경우도
같은 경로로 드러납니다.

```
컬럼 'Dept' 이(가) 테이블에 없습니다.
사용 가능한 컬럼: [Column1, Column2, Column3, Column4]
```

컬럼 목록 자체가 헤더를 못 읽었다는 증거입니다. 진입 시점에 `Column\d+` 패턴으로
차단하는 방식도 써봤지만 제거했습니다 — 이미 위 경로가 잡아내고 있었고,
`r.Column1`로 위치를 다루려는 정상 사용까지 막았기 때문입니다.

### (2) 열 이름 감지 — `new Queryable(table, autoDetectNumeric)`

생성자가 `table.getSchema()`를 훑어 컬럼명 목록과 `컬럼명 → 인덱스` 맵을 만듭니다.
맵은 `LinkedHashMap`이라 컬럼 순서가 보존됩니다.

**왜 미리 만드는가.** 행마다 컬럼명을 찾는 대신 인덱스로 바로 접근하기 위해서입니다.
더 중요한 건, 이 맵이 뒤에서 **체인 각 단계의 "그 시점 컬럼 구성"** 역할을 한다는 점입니다.
`Select`가 컬럼을 갈아끼우면 이 맵도 교체되고, 그 뒤에 등록되는 람다는 새 맵을 붙잡습니다.

### (3) 표현식 컴파일 — `ExpressionEngine.compile`, 딱 1회

파싱은 행 루프 바깥에서 한 번만 일어납니다. 10,000행이든 1행이든 파싱 횟수는 1입니다
(`QueryTest.chainIsParsedOnceForTenThousandRows`가 카운터로 검증합니다).

덤으로, **문법 오류는 첫 행을 읽기 전에** 발생합니다.
10만 행을 다 처리한 뒤 오타 때문에 실패하는 일이 없습니다.

### (4) 샌드박스 — JEXL 문법·리플렉션 제한

표현식은 Bot Agent 프로세스 안에서 평가됩니다. 제한하지 않으면 그대로 임의 코드 실행 경로입니다.
**문법과 리플렉션 양쪽**을 막습니다.

문법(`JexlFeatures`):


| 설정                           | 막는 것                                 |
| ------------------------------ | --------------------------------------- |
| `sideEffect(false)`            | 대입.`Dept = "IT"`는 파싱에서 거부      |
| `newInstance(false)`           | `new`                                   |
| `loops(false)`                 | 반복문                                  |
| `script(false)`                | 문장 나열·블록. 표현식 하나만 허용     |
| `lambda(true)`                 | 람다는**허용**. `r -> ...`이 필요하므로 |
| `strict(true)` / `safe(false)` | 미정의 변수와 null 대상 접근을 예외로   |

리플렉션(`JexlPermissions`): `RESTRICTED`를 기반으로 `java.lang.Object.getClass()`를 추가 차단합니다.
`RESTRICTED`만으로는 `getClass()`가 통과하는데, 이거 하나면 임의 클래스에 도달할 수 있습니다.

`RESTRICTED`는 우리 패키지의 메서드도 함께 거부하므로, 전역으로 푸는 대신
`Fn` · `Queryable` · `QueryRow` **세 클래스만** 예외로 통과시킵니다.
세 클래스가 반환하는 타입은 원시값 아니면 이 패키지 타입뿐이라,
여기서 다른 객체 그래프로 빠져나갈 수 없습니다.

### (5) 체인 구축 — 메서드는 호출되지만 행은 읽지 않는다

`table` 변수에 `Queryable`을 바인딩하고 표현식을 평가하면,
JEXL이 `Where(...)` → `OrderBy(...)` → `Select(...)` 순으로 메서드를 실제 호출합니다.
하지만 **각 메서드는 단계(Stage) 생성자를 리스트에 등록하고 `this`를 반환할 뿐,
원본 행은 한 줄도 읽지 않습니다.**

이 시점에 각 람다는 세 가지를 붙잡습니다(capture).

- **그 시점의 컬럼 구성** — 그래서 `Select` 뒤의 단계는 새 컬럼명으로 해석됩니다
- **연산 순번** — 오류 메시지의 `2번째 연산(3번 행)`이 여기서 나옵니다
- **연산자 이름** — 오류 메시지용

**왜 등록만 하는가.** 실행을 한 번에 몰아야 중간 테이블이 생기지 않고,
조기 종료 신호가 체인 전체를 관통할 수 있기 때문입니다. (7)에서 이어집니다.

이 단계에서 잡히는 오류도 있습니다. `ThenBy`는 직전 연산이 정렬인지 확인하고,
아니면 **행을 읽기 전에** 바로 거부합니다.

### (6) 트리 조립 — 뒤에서 앞으로

`run()`이 등록된 단계들을 **역순으로** 조립합니다.

```
등록 순서 :  Where  →  OrderBy  →  Select
조립 순서 :  Where  ←  OrderBy  ←  Select  ←  종료 단계
             ^head
```

```java
Stage head = terminal;
for (int i = stages.size() - 1; i >= 0; i--) {
    head = stages.get(i).create(head);   // 각 단계가 next를 쥔 채 생성된다
}
```

**왜 역순인가.** 각 단계가 `next.push(row)`로 다음 단계에 넘겨야 하므로,
생성 시점에 `next`가 이미 존재해야 합니다. 뒤에서부터 만들면 이 조건이 자연히 충족되고,
결과적으로 **`head` 하나만 쥐면 체인 전체를 구동**할 수 있습니다.

종료 단계는 액션에 따라 다릅니다. `Collector`(행 수집) · `Presence`(존재 여부) · `Verdict`(조건 판정).

### (7) 실행 — push 방식, 반환값이 중단 신호

원본 행을 하나씩 `head.push(row)`로 밀어넣습니다.

```java
for (Row row : source.getRows()) {
    scanned++;
    if (!head.push(row)) break;   // false = "그만 보내라"
}
head.end();
```

**단계 단위가 아니라 행 단위입니다.** 행 하나가 체인 끝까지 갔다 온 뒤 다음 행이 들어옵니다.
연산자 순서는 표현식에 쓴 순서 그대로이고, 고정된 순서는 없습니다.

```
table.Where(A).OrderBy(B).Select(C) 라고 썼다면

  이 구현        행1 : Where(A) → OrderBy(B) → Select(C)
                 행2 : Where(A) → OrderBy(B) → Select(C)
                 ...

  단계 단위라면  Where(A) 전체 → 중간테이블 → OrderBy(B) 전체 → 중간테이블 → Select(C) 전체
```

SQL처럼 `FROM → WHERE → ORDER BY → SELECT`로 정해진 논리 순서가 없습니다.
LINQ 메서드 구문과 같아서, `OrderBy`를 앞에 쓰면 정렬이 먼저 돕니다.

`push`의 반환값이 곧 **역방향 제어 신호**입니다.
`Take(3)`이 3개를 채우면 `false`를 반환하고, 그 값이 상류로 그대로 타고 올라가
원본 읽기 루프를 멈춥니다.

```
10,000행 테이블에서
  table.Where(r -> r.Dept == "IT").Take(3)   →  5행만 읽음
  table.Any(r -> r.Dept == "HR")             →  2행만 읽음
```

**체인 전체를 한 액션이 받기 때문에 가능한 일입니다.**
연산자마다 액션을 나눴다면 단계마다 테이블이 실체화되고, 중단할 지점 자체가 없습니다.

### (8) 버퍼링 지점은 `OrderBy` 하나뿐

`OrderBy`는 `push`에서 행을 버퍼에 쌓기만 하고, 입력이 끝나는 `end()` 시점에
정렬한 뒤 하류로 밀어냅니다.

**왜 여기만 버퍼링하는가.** 정렬은 전체 입력을 보기 전에는 첫 행조차 확정할 수 없습니다.
원리상 불가피한 지점입니다. 그래서 **`OrderBy` 앞쪽의 조기 종료는 정렬 지점에서 막힙니다.**
.NET LINQ도 동일하게 동작합니다.

`ThenBy`는 새 버퍼를 만들지 않습니다. 직전 정렬 단계에 보조 키를 추가할 뿐입니다.
정렬을 두 번 돌리지 않기 위해서고, 이 때문에 정렬 바로 뒤에서만 쓸 수 있습니다.

### (9) 결과 변환 — 여기서만 셋이 갈라진다


| 액션             | 종료 단계                  | 변환                                 |
| ---------------- | -------------------------- | ------------------------------------ |
| Query to Table   | `Collector`                | 모인 행 + 확정된 컬럼명 → 새`Table` |
| Query to Number  | `Collector` 후 선택자 평가 | `Double`                             |
| Query to Boolean | `Presence` / `Verdict`     | `Boolean`                            |

`Query to Table`은 스키마를 새로 만듭니다. 컬럼 타입은
**각 컬럼에서 비어 있지 않은 첫 값의 자바 타입**으로 정합니다(없으면 STRING).

반환 직전에 결과 타입을 검사합니다. 액션과 종료 연산자가 어긋나면
"무엇으로 끝내야 하는지"를 예시와 함께 알려줍니다.

### (10) 오류 메시지 — 어느 연산, 몇 번 행

실패는 `2번째 연산(3번 행) Where 평가 실패: ...` 형태로 보고됩니다.
연산 순번은 (5)에서, 행 번호는 실행 중에 붙습니다.

JEXL이 우리 예외를 자기 예외로 감싸기 때문에 `catch` 타입만으로는 구분되지 않습니다.
그래서 원인 사슬을 훑어 `BotCommandException`이 있으면 꺼내서 그대로 던집니다.
JEXL이 덧붙인 파서 위치 문구가 사용자 메시지를 덮지 않게 하기 위한 처리입니다.

---

## 3. 표현식 작성법과 실제 동작

### 기본 형태

`table`로 시작하는 **표현식 하나**입니다. 문장 나열이나 블록은 문법에서 막혀 있습니다.

```
table.Where(r -> r.dept == "부서1" && toNumber(r.age) > 30)
     .OrderByDescending(r -> toNumber(r.age))
     .Select(["name", "age"], r -> [r.name, toNumber(r.age)])
```

- 람다 인자는 하나. 관례적으로 `r`을 씁니다.
- 컬럼 접근은 `r.컬럼명` 또는 `r['컬럼명']`. 둘 다 같은 경로로 들어옵니다.
- 행 번호는 `r._rowIndex` (0부터). 컬럼과 달리 행에는 이름이 없어 별도로 제공합니다.

`_rowIndex`는 **그 연산자에 도착한 순번**입니다. 첫 단계라면 원본 행 번호와 같지만,
`Where` 뒤의 두 번째 `Where`에서는 걸러지고 남은 행들의 순번입니다.

### 컬럼명은 대소문자를 가립니다 — 오타는 오류입니다

```
컬럼 'Dept' 이(가) 테이블에 없습니다. 사용 가능한 컬럼: [name, dept, age, salary]
```

JEXL의 `strict(true)`는 컨텍스트 변수에만 걸리고 **property 접근에는 걸리지 않습니다.**
그냥 두면 `r.Dpet` 같은 오타가 `null`이 되고, 조건식이 전부 false가 되어 **0건이 조용히**
나옵니다. 그래서 `QueryRow.get()`이 직접 예외를 던집니다.

Filter 액션은 컬럼을 컨텍스트 변수로 바인딩하므로 `strict(true)`에 걸려 원래도 예외였지만,
JEXL 원문은 이랬습니다.

```
0번 행 평가 실패 [Dept == "IT"]: ...ExpressionEngine.compile:121@1:1 variable 'Dept' is undefined
```

파서 좌표가 붙고 어떤 컬럼을 쓸 수 있는지도 알려주지 않아, `RowEvaluator`가 이를 잡아
위와 같은 문구로 바꿉니다. 두 경로의 메시지가 같습니다.

### 셀 값의 타입

기본값에서 셀은 **문자열**입니다. 숫자 비교는 `toNumber(...)`로 명시합니다.

`Auto-detect numeric columns`를 켜면 숫자로 읽히는 문자열이 `Double`로 바인딩됩니다.
단 **앞자리 0이 의미를 갖는 값(`01234`, `0087`)은 문자열로 유지**됩니다.
우편번호와 사번이 숫자로 바뀌어 앞자리 0을 잃는 걸 막기 위한 예외입니다
(`0`, `0.5`는 정상적으로 숫자가 됩니다).

이게 기본값이 아닌 이유도 같습니다. 자동 변환은 편하지만 되돌릴 수 없는 손실을 만듭니다.

### 사용 가능한 함수

`Fn` 클래스에 있는 것만 호출할 수 있습니다. 이 목록에 없는 메서드는 호출 불가입니다.


| 함수                         | 동작                                                       |
| ---------------------------- | ---------------------------------------------------------- |
| `toNumber(값)`               | 숫자 변환. 천 단위 구분자·앞뒤 공백 허용.**실패 시 null** |
| `toNumberOr(값, 기본값)`     | 변환 실패 시 기본값                                        |
| `isEmpty(값)`                | null이거나 공백만이면 true                                 |
| `eqIgnoreCase(a, b)`         | 대소문자 무시 비교                                         |
| `contains(문자열, 부분)`     | 포함 여부. 대소문자 구분                                   |
| `startsWith(문자열, 접두어)` | 시작 여부. 대소문자 구분                                   |

`toNumber`가 실패하면 `null`이고, strict 산술에서 null 피연산자는 예외입니다.
즉 **빈 셀 하나가 그 행에서 쿼리를 실패시킵니다.** 미입력과 0을 구분할 필요가 없다면
`toNumberOr(r.salary, 0) >= 7000`을 쓰십시오.

### 연산자별 실제 동작

**중간 연산자** (세 액션 공통)


| 연산자                                | 동작 방식                              |
| ------------------------------------- | -------------------------------------- |
| `Where(람다)`                         | 행별 즉시 판정. 버퍼 없음              |
| `OrderBy` / `OrderByDescending(람다)` | **버퍼링 후 정렬.** 안정 정렬          |
| `ThenBy` / `ThenByDescending(람다)`   | 직전 정렬에 보조 키 추가. 새 버퍼 없음 |
| `Select([컬럼명], 람다)`              | 행별 즉시 변환.**컬럼 구성 교체**      |
| `Take(n)`                             | n개를 채우면 상류 중단                 |
| `Skip(n)`                             | 앞 n개를 세면서 버림                   |

정렬 비교 규칙: null이 앞섭니다. 같은 타입은 자연 순서, 타입이 다르면 문자열로 비교합니다.
안정 정렬이라 동점 행의 원래 순서는 유지됩니다(LINQ와 동일).

**문자열 정렬은 사전순입니다.** `"10" < "9"`가 됩니다.
숫자로 정렬하려면 `OrderBy(r -> toNumber(r.age))`처럼 명시하십시오.

### `ThenBy`는 정렬 바로 뒤에서만

```
table.OrderBy(r -> r.dept).ThenBy(r -> toNumber(r.age))        OK
table.OrderBy(r -> r.dept).Where(...).ThenBy(...)              오류
```

중간에 `Select`가 끼면 정렬 버퍼가 **옛 컬럼 구성으로 담긴 행**을 들고 있는데
보조 키는 새 컬럼명을 참조하게 되어 어긋납니다.
그 외 연산자가 끼는 경우는 어긋나진 않지만, 코드에 쓴 순서와 실제 실행 순서가 달라져
읽는 사람을 속입니다. 두 경우 다 체인 구축 시점에 거부합니다.

### `Select` 이후에는 새 컬럼명만 존재합니다

```
table.Select(["Who"], r -> [r.Name]).Where(r -> r.Who == "Dan")     OK
table.Select(["Who"], r -> [r.Name]).Where(r -> r.Name == "Dan")    오류 (Name은 이미 없음)
```

`Select`는 컬럼 구성을 **교체**합니다. 원본 컬럼은 그 지점에서 사라집니다.
필터를 원본 컬럼으로 걸 생각이라면 `Where`를 **먼저** 쓰십시오.
컬럼명 오류는 조용히 넘어가지 않고 즉시 실패하므로 잘못 쓰면 바로 드러납니다.

`Select`가 컬럼명을 별도 인자로 받는 이유는 자바 쪽 제약입니다.
JEXL 맵 리터럴은 `HashMap`으로 생성되어 **키 순서가 보존되지 않고**,
C#의 익명 타입 `new { r.Name }`에 해당하는 문법이 자바에 없습니다.
컬럼명 개수와 람다가 만든 값 개수가 다르면 오류입니다.

### 실행 계획은 없습니다 — 쓴 순서 그대로 돕니다

옵티마이저가 없습니다. 재배치도, 술어 밀어내기(predicate pushdown)도 하지 않습니다.
따라서 **순서를 잘 쓰는 것이 곧 성능**입니다.

```
table.Where(r -> r.dept == "IT").OrderBy(r -> toNumber(r.age))   IT만 정렬
table.OrderBy(r -> toNumber(r.age)).Where(r -> r.dept == "IT")   전부 정렬 후 필터
```

두 결과는 같지만 두 번째는 버릴 행까지 전부 정렬합니다.
`Take`도 마찬가지입니다. `OrderBy` 뒤의 `Take`는 정렬을 막지 못합니다.

집계(`Sum` / `Average` / `Min` / `Max`)는 **체인 결과가 모두 모인 뒤** 실행됩니다.
그래서 집계 앞에서는 조기 종료가 일어나지 않습니다. 원리상 당연합니다 — 합계는 전부 봐야 나옵니다.

### 빈 입력에서의 동작 (LINQ와 동일)


| 연산자                    | 결과                                |
| ------------------------- | ----------------------------------- |
| `Sum`                     | 0                                   |
| `Average` / `Min` / `Max` | 오류                                |
| `First`                   | 오류 (`FirstOrDefault`는 빈 테이블) |
| `Any`                     | false                               |
| `All`                     | true                                |

### 예시

```
# 조건 + 정렬 + 컬럼 선택
table.Where(r -> r.dept == "부서1" && toNumber(r.age) > 30)
     .OrderByDescending(r -> toNumber(r.age))
     .Select(["name", "age"], r -> [r.name, toNumber(r.age)])

# 다중 키 정렬
table.OrderBy(r -> r.dept).ThenByDescending(r -> toNumber(r.salary))

# 페이징
table.Where(r -> r.dept == "IT").Skip(20).Take(10)

# 상위 1건
table.OrderByDescending(r -> toNumber(r.salary)).First()

# 숫자 반환
table.Where(r -> r.dept == "부서1").Count()
table.Sum(r -> toNumberOr(r.salary, 0))

# true/false 반환
table.Any(r -> toNumber(r.age) >= 60)
table.All(r -> !isEmpty(r.email))
```
