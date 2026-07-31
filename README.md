# CustomPackageExample

Automation Anywhere A360 커스텀 패키지. 테이블의 행을 표현식으로 걸러내는 액션을 제공합니다.

## 필요 환경

| | 버전 | 용도 |
|---|---|---|
| JDK | 21 (또는 17) | Gradle 데몬. `JAVA_HOME`을 그대로 씁니다 |
| JDK | 11 | 소스 컴파일. Gradle toolchain이 자동으로 찾습니다 |
| Gradle | 8.14.5 | 래퍼에 포함. 따로 설치할 필요 없습니다 |

클론한 뒤 별도 설정 없이 바로 빌드됩니다.

### 컴파일만 JDK 11인 이유

SDK 1.7.0의 어노테이션 프로세서(`BotCommandAnnotationProcessor`)가 JDK 21의
`AnnotationProxyMaker`와 맞지 않아 `multiple_returns()`에서 `AnnotationTypeMismatch`로
죽습니다. `sourceCompatibility`만 11로 두면 어노테이션 프로세서는 여전히 데몬 JVM에서
돌기 때문에, `build.gradle`의 toolchain으로 컴파일 JVM 자체를 11로 고정했습니다.

데몬 JVM은 이 제약과 무관합니다.

### Gradle을 9로 올리지 않은 이유

플러그인 두 개가 Gradle 9에서 제거되는 API를 씁니다. 우리 코드가 아니라 손댈 수 없습니다.

- shadow 8.1.1 → `FileTreeElement.getMode()` (Gradle 9에서 제거)
- A360 command-codegen → 실행 시점의 `Task.project` 접근 (Gradle 10에서 오류)

8.14.5가 8.x 마지막 버전입니다. 두 플러그인이 갱신되기 전까지는 여기 머무는 게 맞습니다.

## 빌드

```powershell
.\gradlew clean build shadowJar
```

산출물은 `build\libs\CustomPackageExample-2.11.0.jar` 입니다. 이 jar를 Control Room에
업로드하면 액션이 나타납니다.

## 테스트

```powershell
.\gradlew test
```

테스트는 Control Room 없이 순수 JUnit으로 돕니다. `Table` / `Row` / `Schema`는 SDK
클래스지만 `new`로 만들 수 있어서, 액션 로직을 로컬에서 그대로 검증할 수 있습니다.

## 제공하는 액션

액션 이름은 `입력 언어 → 출력 타입` 규칙을 따릅니다. 반환 타입이 액션 선택 시점에
확정되므로 Bot Editor가 변수 타입을 검사할 수 있습니다.

| 액션 | 반환 | 입력 언어 |
|---|---|---|
| LinqTable: Filter to Table | TABLE | 조건식 하나 |
| LinqTable: Query to Table | TABLE | 람다 체인 |
| LinqTable: Query to Number | NUMBER | 람다 체인 |
| LinqTable: Query to Boolean | BOOLEAN | 람다 체인 |

네 액션 모두 `Source table` / `Query`(또는 `Condition`) / `Auto-detect numeric columns`를
입력으로 받습니다. 원본 테이블은 어느 경우에도 바뀌지 않습니다.

### Filter to Table

조건식 하나로 행을 거릅니다. 컬럼명을 자유 변수로 씁니다.

```
Dept == "IT" && toNumber(Years) > 3
_rowIndex < 100
startsWith(Zip, "01")
```

### Query to Table / Number / Boolean

`table`로 시작하는 람다 체인을 씁니다. 컬럼은 `r.컬럼명`, 행 번호는 `r._rowIndex`입니다.

```
table.Where(r -> r.dept == "부서1" && toNumber(r.age) > 30)
     .OrderByDescending(r -> toNumber(r.age))
     .Select(["name", "age"], r -> [r.name, toNumber(r.age)])

table.Where(r -> r.dept == "부서1").Count()
table.Sum(r -> toNumber(r.salary))
table.Any(r -> toNumber(r.age) >= 60)
```

**중간 연산자** (세 액션 공통)

| | |
|---|---|
| `Where(람다)` | 조건을 만족하는 행만 |
| `OrderBy(람다)` / `OrderByDescending(람다)` | 키 정렬. 안정 정렬이다 |
| `Select([컬럼명 목록], 람다)` | 컬럼 선택·계산 |
| `Take(n)` / `Skip(n)` | 개수 제한 · 건너뛰기 |

**종료 연산자** (액션별로 다름)

| 액션 | 연산자 |
|---|---|
| Query to Table | `First()` · `FirstOrDefault()` 또는 생략 |
| Query to Number | `Count()` · `Sum(람다)` · `Average(람다)` · `Min(람다)` · `Max(람다)` |
| Query to Boolean | `Any()` · `Any(람다)` · `All(람다)` |

`Select`가 컬럼명을 따로 받는 것은 JEXL 맵 리터럴이 `HashMap`이라 키 순서가 보존되지
않기 때문입니다. C#의 `new { r.Name }`에 해당하는 익명 타입이 자바에 없습니다.

### 사용 가능한 함수

```
toNumber(값)  toNumberOr(값, 기본값)  isEmpty(값)
eqIgnoreCase(a, b)  contains(문자열, 부분)  startsWith(문자열, 접두어)
```

이 목록에 없는 메서드는 호출할 수 없습니다. 표현식은 Bot Agent 프로세스에서 평가되므로
문법(대입·`new`·반복문·블록)과 리플렉션 범위를 모두 제한합니다.

### 조기 종료

`Take` / `First` / `Any` / `All`은 답이 정해지는 순간 원본 읽기를 멈춥니다.

```
10,000행 테이블에서
  table.Where(r -> r.Dept == "IT").Take(3)   5행만 읽음
  table.Any(r -> r.Dept == "HR")             2행만 읽음
```

체인 전체를 한 액션이 받기 때문에 가능합니다. 연산자마다 액션을 나눴다면 단계마다
테이블이 만들어져 중단할 지점이 없습니다.

단 `OrderBy`는 전체 입력이 있어야 첫 행을 낼 수 있으므로 그 앞의 조기 종료는
정렬 지점에서 막힙니다. LINQ도 동일합니다.

### 빈 입력에서의 동작

LINQ와 맞췄습니다.

| | |
|---|---|
| `Sum` | 0 |
| `Average` / `Min` / `Max` | 오류 |
| `First` | 오류 (`FirstOrDefault`는 빈 테이블) |
| `Any` | false |
| `All` | true |

### 알아둘 것

- **컬럼명은 대소문자를 가립니다.** 오타는 "0건"이 아니라 오류로 보고됩니다. 조용히 빈
  결과가 나와서 원인을 못 찾는 상황을 막기 위한 것입니다.
- **`Contains header` 옵션을 켜고 읽으십시오.** 끄면 컬럼명이 `Column1, Column2...`로
  생성돼 표현식에 쓸 수 없습니다. 이 경우 액션이 거부하고 이유를 알려줍니다.
- **기본값에서 셀은 문자열입니다.** 숫자 비교는 `toNumber(...)`로 명시하거나
  `Auto-detect numeric columns`를 켜십시오.
- **앞자리 0은 보존됩니다.** 자동 변환을 켜도 우편번호 `01234`, 사번 `0087`은 문자열로
  남습니다.
