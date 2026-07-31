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

### LinqTable: Filter

조건식을 만족하는 행만 남긴 새 테이블을 반환합니다. 원본 테이블은 바뀌지 않습니다.

| 입력 | 설명 |
|---|---|
| Source table | 원본 테이블 |
| Condition | 행마다 평가할 조건식. `true`/`false`를 반환해야 합니다 |
| Auto-detect numeric columns | 켜면 숫자로 보이는 셀을 숫자로 다룹니다 (기본 꺼짐) |

컬럼명을 그대로 변수로 씁니다. 행 번호는 `_rowIndex`(0부터)로 참조합니다.

```
Dept == "IT" && toNumber(Years) > 3
_rowIndex < 100
startsWith(Zip, "01")
```

사용 가능한 함수는 `toNumber`, `eqIgnoreCase`, `contains`, `startsWith`, `isEmpty`
다섯 개입니다. 이 목록에 없는 메서드는 호출할 수 없습니다.

### 알아둘 것

- **컬럼명은 대소문자를 가립니다.** 오타는 "0건"이 아니라 오류로 보고됩니다. 조용히 빈
  결과가 나와서 원인을 못 찾는 상황을 막기 위한 것입니다.
- **`Contains header` 옵션을 켜고 읽으십시오.** 끄면 컬럼명이 `Column1, Column2...`로
  생성돼 표현식에 쓸 수 없습니다. 이 경우 액션이 거부하고 이유를 알려줍니다.
- **기본값에서 셀은 문자열입니다.** 숫자 비교는 `toNumber(...)`로 명시하거나
  `Auto-detect numeric columns`를 켜십시오.
- **앞자리 0은 보존됩니다.** 자동 변환을 켜도 우편번호 `01234`, 사번 `0087`은 문자열로
  남습니다.
