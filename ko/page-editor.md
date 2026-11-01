# 페이지 편집기와 플러그인 구성 요소

SDK 1.4 이상이 필요합니다. 선택 사항인 `page-editor` 플러그인을 설치하고 시작한 다음, 관리자 로그인 후 탐색 메뉴에서 페이지 편집기를 엽니다. 코어는 로그인 페이지를 제공합니다. 다른 페이지는 해당 소유자가 편집 대상으로 선언합니다.

## 편집, 게시, 복원

1. 페이지를 선택하고 ‘구성 요소 추가’에서 캔버스로 끌어 놓습니다. 버튼에 초점을 맞추고 Enter 또는 Space를 눌러 추가할 수도 있습니다.
2. 레이어에서 인스턴스를 선택하여 속성과 스타일을 편집합니다. 컨테이너를 중첩할 수 있으며 이동, 중첩, 밖으로 이동, 삭제 버튼을 키보드로 조작할 수 있습니다. 좁은 화면 모드에서는 반응형 스타일을 설정합니다.
3. 초안을 저장하고 다시 불러오거나 미리 봅니다. 미리 보기에서는 업무 작업을 실행하지 않습니다. 게시는 현재 편집 내용을 저장하고 공개 버전을 대체합니다. 공개 페이지의 구성 요소 설정도 공개됩니다.
4. 이전 게시 버전을 복원하면 공개 버전과 초안이 함께 대체됩니다. 다른 창이 먼저 저장한 경우 버전 충돌이 발생하므로 다시 불러온 뒤 변경하세요.

「초안 다시 불러오기」와 「이전 게시 버전 복원」은 상단의 「더 보기」 메뉴에 있습니다. 이전 게시 버전이 없으면 복원 버튼을 사용할 수 없습니다.

코어가 로그인 폼, 계정 인증, 초대 코드와 리디렉션을 유지합니다. 게시된 페이지와 작업 보호는 편집기를 중지해도 유지됩니다. 기본 레이아웃은 현재 브라우저 화면만 바꾸며 서버의 검증 요구를 지우지 않습니다. 공식 기록, 필수 구성 요소 또는 정책을 사용할 수 없으면 보호된 작업을 거부합니다. 로그인한 사용자의 리디렉션은 유지됩니다.

누락되거나 호환되지 않는 구성 요소는 유형, 설정 버전, 인스턴스 ID와 설정을 보존합니다. 다른 레이아웃 변경은 저장할 수 있지만 불완전한 페이지는 게시할 수 없습니다. 호환되는 플러그인을 다시 활성화하거나 해당 플러그인의 설정 마이그레이션을 명시적으로 실행하세요. 커밋 전 실패는 이전 공식 버전을 보존합니다. 커밋 기록을 저장한 뒤 활성화가 실패하면 작업을 거부한 상태로 커밋된 리비전에서 복구합니다.

왼쪽에서 ‘구조’와 ‘컴포넌트 추가’를 전환하세요. 캔버스나 구조 목록에서 내용을 선택한 뒤 오른쪽에서 속성과 스타일을 편집합니다. 이동과 삭제도 오른쪽에서 할 수 있습니다. ‘페이지 설정’의 브라우저 탭 제목은 탭 제목만 바꿉니다. ‘미리보기’는 실제 레이아웃을 중앙에 펼쳐 보여 줍니다. ‘편집으로 돌아가기’를 눌러도 저장하지 않은 변경 사항은 유지됩니다. 로그인 페이지의 컨트롤과 스타일을 사용하며 로그인이나 업무 요청은 전송하지 않습니다.

## 폼 검증과 리소스 승인

페이지 owner가 선언한 폼 영역에 검증 컴포넌트를 끌어 놓으면 호환 대상이 하나일 때 자동 선택됩니다. 여러 개면 보호할 작업을 선택하세요. 일치하는 대상이 없으면 초안만 저장할 수 있고 보호 설정은 게시할 수 없습니다. 같은 대상의 참여 정책은 모두 필수이며 중복은 거부됩니다. 복사, 이동, 실행 취소는 인스턴스를 분리하고 사용 중인 proof를 복사하지 않습니다.

게시 전에 영향받는 작업, 필수 정책 변경, 필요한 리소스 출처를 표시합니다. 확인 후 서버는 현재 카탈로그와 리비전을 다시 검사하고 대상 작업의 새 요청을 차단한 다음 진행 중인 작업이 끝나면 페이지, 연결, 권한을 함께 커밋합니다. 초안과 미리보기는 실제 보호를 바꾸거나 실제 챌린지를 생성하지 않습니다. 같은 작업을 참조하는 페이지들의 설정은 일치해야 합니다. 정적 `setup.login-verification-policies`는 계속 로그인 필수 요구로 유지됩니다.

HTML, 제목, 투영, ETag, CSP는 같은 공식 리비전을 사용합니다. 이전 문서는 변경을 감지하면 다시 탐색하고 백엔드는 이전 proof를 거부합니다. 컴포넌트는 Worker와 다른 인스턴스 리소스를 해제해야 합니다. 사용자 HTML/JS 샌드박스는 기존 제약을 유지하며 CAPTCHA 플러그인의 외부 스크립트 권한을 받지 않습니다.

공식 상태는 `data/web-publications/`, 등록 기록은 `config/web-publications.enrolled`에 있습니다. 캐시로 취급하여 삭제하지 마세요. 편집기 중지나 주 데이터베이스 장애로 보호가 지워지지 않습니다. 오프라인 복구 시 인스턴스를 중지하고 같은 실행 디렉터리에서 아래 로컬 명령을 실행합니다. 조회 결과의 후보 파일과 SHA-256을 확인한 뒤 검증된 완전한 후보만 복원하세요. 수정 시간만으로 선택하지 마세요.

```text
java -jar "<boot-jar>" --no-gui --web-publications
java -jar "<boot-jar>" --no-gui --web-publications --publication-restore=<candidate.json> --publication-sha256=<sha256>
```

`<boot-jar>`는 실제 시작 JAR로, `<candidate.json>`과 `<sha256>`은 조회 후 검증한 값으로 바꾸세요.

## 머리글, 바닥글 및 로그인 카드

새 초안은 페이지 owner가 제공한 기존 레이아웃을 불러옵니다. 머리글, 본문 또는 바닥글을 선택하여 스타일을 바꾸거나 추가 패널에서 삽입할 수 있습니다. 컨테이너의 ‘영역 유형’에서 일반 컨테이너와 각 영역을 전환합니다.

등록 정보를 넣으려면 레이어에서 바닥글을 선택하고 텍스트나 링크를 추가한 뒤 문구와 주소를 입력하세요. 기본 레이아웃은 세로 flex 배치이며 본문이 남은 공간을 채웁니다. 내용이 짧으면 바닥글이 화면 아래에 놓이고, 길면 본문 뒤로 이어져 내용을 가리지 않습니다.

로그인 카드의 제목, 사용자 이름, 비밀번호 및 버튼을 선택할 수 있습니다. 컨트롤 사이에 텍스트나 플러그인을 끌어 놓거나, 컨트롤을 선택한 뒤 추가 버튼에서 Enter를 누르면 그 뒤에 삽입됩니다. 필수 컨트롤은 삭제, 복제하거나 선언된 부모 영역 밖으로 옮길 수 없습니다. 일반 컨테이너로 그룹과 스타일을 조정할 수 있습니다. ‘처음에 숨겨진 영역 표시’로 초대 카드를 편집해도 실제 페이지의 전환 동작은 바뀌지 않습니다.

‘더 보기 → 페이지 기본 레이아웃 복원’은 저장하지 않은 편집 내용만 바꿉니다. 실제 페이지에 반영하려면 저장하고 게시하세요. 실제 로그인 페이지의 기본 레이아웃 버튼은 현재 브라우저 화면만 복원하며 서버 초안과 게시 기록을 삭제하지 않습니다. 필수 영역이 누락되면 설명과 기본 로그인 페이지 미리보기가 표시되며 저장과 게시는 사용할 수 없습니다. ‘이 레이아웃으로 시작’은 저장하지 않은 캔버스만 바꿉니다. 명시적으로 저장하기 전까지 원래 초안은 보존됩니다.

## 컴포넌트 CSS

컴포넌트를 선택하고 오른쪽 “스타일”에서 “사용자 지정 CSS”를 펼치면 CodeMirror 편집 영역을 사용할 수 있습니다. “레이아웃”, “모양”, “사용자 지정 CSS”는 각각 펼치거나 접을 수 있습니다. “스타일”을 접으면 그룹 전체가 접히고 아직 적용하지 않은 CSS는 유지됩니다. 편집기는 줄 번호, 구문 강조, 지원 속성 자동 완성과 오류 위치 표시를 제공합니다. 물음표 버튼에서 입력 형식과 키보드 단축키를 확인할 수 있습니다. 오른쪽 위 대각선 화살표로 전체 화면을 열고, 다시 누르거나 Esc로 사이드바로 돌아옵니다. 입력과 실행 취소 기록은 유지됩니다. 다음과 같이 입력한 뒤 “스타일 적용”을 선택합니다.

```css
padding: 16px;
border-radius: 8px;
```

스타일은 선택한 컴포넌트의 루트 요소와 현재 캔버스 너비에 적용됩니다. 텍스트 등 상속 가능한 속성은 CSS 상속 규칙을 따릅니다. 좁은 화면으로 전환하면 해당 너비의 스타일을 따로 지정할 수 있습니다. 빈 입력을 적용하면 현재 너비의 스타일을 제거합니다. 적용은 실행 취소할 수 있으며, 스타일은 초안에 저장되어 미리 보기와 게시에 사용됩니다. 별도의 `.css` 파일은 저장하지 않습니다.

Ctrl/Cmd+Enter로 적용하고 Tab으로 다음 컨트롤로 이동합니다. Esc는 자동 완성 목록이나 도움말을 먼저 닫은 뒤 전체 화면을 종료하거나 편집 영역을 접습니다. 접어도 적용하지 않은 입력은 유지되며, “변경 취소”를 누르면 현재 스타일을 다시 불러옵니다. 저장, 미리 보기, 게시 전에 CSS 변경을 적용하거나 버려야 합니다. 컴포넌트나 캔버스 너비를 바꿔도 입력은 유지되지만 다른 대상에 적용할 수 없습니다.

패널 펼치기, 컴포넌트 이동, 작업 결과 표시에 중단 가능한 전환 효과를 사용합니다. 시스템의 동작 줄이기 설정을 켜면 애니메이션은 멈추고 상태와 결과는 계속 표시됩니다.

아래 속성과 유효한 값을 지원합니다. 각 값은 최대 128자입니다. 선택자, 주석, `@media` / `@import`, 외부 리소스, `!important`, `display: none`, 사용자 정의 속성 선언은 지원하지 않습니다. 잘못된 입력은 유지되며 오류 메시지가 표시됩니다.

`display`, `flex-direction`, `flex-wrap`, `justify-content`, `align-items`, `gap`, `padding`, `padding-top`, `padding-right`, `padding-bottom`, `padding-left`, `margin`, `margin-top`, `margin-right`, `margin-bottom`, `margin-left`, `width`, `min-width`, `max-width`, `min-height`, `font-size`, `font-weight`, `line-height`, `text-align`, `color`, `background-color`, `border`, `border-color`, `border-width`, `border-style`, `border-radius`, `box-shadow`, `grid-template-columns`, `flex`, `order`.

## 구성 요소 제공

새 구성 요소를 추가할 때 수정할 대상은 해당 플러그인과 독립된 공개 업무 계약입니다. 호스트, 중앙 SDK, 편집기의 유형 목록을 수정할 필요가 없습니다. [독립 체크리스트 예제](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-app/src/test/resources/page-components)는 중첩 `items[]`, 사용자 지정 속성 편집기, 두 인스턴스와 생성된 업무 클라이언트를 보여 주는 검증용 플러그인입니다.

| 파일 또는 진입점 | 개발자가 구현할 내용 |
| --- | --- |
| 업무 계약과 값 타입 | 작업과 설정 타입을 선언하고 SDK 도구로 JSON Schema, 브라우저 클라이언트, Java HTTP adapter 생성 |
| `PageContribution` Bean | `PageComponent` 선언, 업무 검증과 필요한 설정 마이그레이션 구현 |
| 렌더링 모듈 | `mount(root, context)`를 export하고 `update(properties)`, `destroy()` 반환 |
| 속성 편집 모듈 | 복잡한 데이터를 위한 `mountProperties(root, context)`를 export하고 `context.change(value)`로 전체 설정 전달 |
| 설정 클래스와 설명자 | Bean, 생성 adapter, service imports/exports, 계약 의존성, 최소 SDK 선언 |
| 정적 자원, 라우트, i18n | 동일 출처 모듈과 사전 제공. 공개 렌더링 모듈은 실제 PUBLIC 라우트 필요 |

`PageComponent`는 안정적인 `owner:type`, 양수 설정 버전, label key/namespace, 렌더링 및 선택적 속성 편집 URL, schema/defaults JSON, `publicRenderable`, 입출력 포트 이름을 선언합니다. 유형의 owner 접두사는 실제 플러그인 owner와 일치해야 하며 중복 유형은 거부됩니다. 카탈로그는 여러 owner를 집계합니다. 단일 provider용 `PluginServices` key를 구성 요소 목록으로 사용하지 않습니다.

`PageContribution.componentCategories()`에서 `PageComponentCategory(id, labelKey, namespace, descriptionKey, components)`를 반환합니다. ID는 `owner:local-id`, 제목과 선택적 설명은 i18n key, 멤버는 표시 순서의 해당 플러그인 컴포넌트 유형입니다. 빈 분류, 중복 ID, 중복 소속, 알 수 없거나 다른 플러그인의 유형은 거부됩니다. 미분류 컴포넌트는 플러그인 표시 이름 아래에 모입니다. 분류는 플러그인 철회 시 함께 사라지며 페이지 바인딩이나 작업 권한을 변경하지 않습니다. 분류 수는 컴포넌트 수를 넘을 수 없으며 페이지 및 컴포넌트 선언과 함께 UTF-8 1 MiB 예산을 사용합니다.

Schema는 현재 SDK 생성기와 같은 서버 검증기가 지원하는 의미를 사용합니다. 임의의 JSON Schema draft를 지원하지 않습니다. 기본 inspector는 문자열, 불리언, 숫자를 처리하고 배열과 객체에는 플러그인 속성 편집기를 제공합니다. `validate(type, json)`으로 업무 조건을 검사하고 `migrate(type, fromVersion, json)`으로 변환된 설정을 반환합니다. 마이그레이션은 기본적으로 지원하지 않으며 반환값은 서버가 다시 검증합니다.

Plugin API JAR의 `META-INF/pixivdownload/web/page-components.d.mts`가 브라우저 계약입니다. context는 인스턴스 ID, `edit/preview/display`, 순수 값 속성, 취소, 번역, fetch, 정리, 선언된 포트 연결을 제공하며 GrapesJS 객체를 노출하지 않습니다. 생성 클라이언트에 `createClient(context.fetch)`를 전달하고 호출에 `context.signal`을 사용하세요. 추가 구독, 이벤트와 타이머는 `context.onCleanup`으로 해제하고 취소 후 늦게 도착한 결과는 적용하지 마세요. 이전 publication의 요청은 교체된 플러그인으로 전환되지 않습니다. 기존 서버 인증은 유지되며 브라우저 연결 해제만으로 서버 작업 종료를 판단할 수 없습니다.

플러그인 코드는 신뢰하여 설치한 코드이며 iframe은 보안 sandbox가 아닙니다. 편집·미리 보기에서 native fetch로 업무 쓰기를 우회하지 마세요. 설정에는 함수, 스크립트, 실행 핸들, 인증 token을 저장하지 않습니다. 포트 payload와 검증은 업무 계약이 정의합니다. 페이지는 임의 URL, 리플렉션 메서드 이름, 스크립트 실행 없이 선언된 포트를 연결합니다.

## 페이지 연결과 저장

`EditablePage`는 `regions`, `defaultProject`, `previewModuleUrl`, `stylesheets`도 선언합니다. 각 `PageRegion(id, labelKey, parent, container, previewHidden)`은 정확히 한 번 배치해야 합니다. `parent`는 가장 가까운 제어 부모 영역이며 빈 문자열이면 다른 제어 영역 안에 배치할 수 없습니다. `container=true`인 영역만 자식 노드를 허용합니다. 서버는 페이지 JSON의 권한 주장 대신 owner의 선언을 검증합니다.

`defaultProject`는 순수 값으로 된 `{"format":1,"project":{...}}`입니다. 빈 문자열이면 영역의 부모·자식 관계로 최소 레이아웃을 만듭니다. 노드는 `page-box`(`role`은 `section/header/main/footer`), `page-text`, `page-link`, `page-region`, `page-component`를 지원합니다. 전체 예제는 코어의 `login/login-layout.json`에 있습니다. 링크 `href`는 사이트 루트 기준 경로나 HTTP(S) 주소만 허용하며 최대 2048자입니다. 링크는 탐색용이며 업무 호출 진입점이 아닙니다.

미리 보기 모듈의 `mount(root, context)`는 `context.properties.region`으로 지정된 말단 영역의 읽기 전용 화면을 그립니다. 컨테이너의 자식은 편집기가 구성합니다. 자원은 현재 owner의 것이어야 하며 CSS는 최대 16개까지 선언합니다. publication 검증 및 철회가 적용되고 익명 데이터에는 미리 보기 모듈 주소가 포함되지 않습니다. `attachPage.onMounted`로 기존 외곽 화면을 숨기고 `onFallback`으로 복원할 수 있습니다. runtime은 이동한 영역의 원래 DOM, 자식 및 스타일을 복원합니다.

페이지 owner는 `PageContribution.pages()`에서 `EditablePage`로 ID, 표시 정보, 실제 경로, 공개 여부와 필수 영역을 제공합니다. `/js/pixiv-pages.mjs`의 `attachPage`에 렌더링 root, 기존 영역 DOM, 기본 레이아웃 버튼과 제목 callback을 전달합니다. 코어의 `login/login-page.mjs`를 참고하세요. 기본 페이지와 이벤트를 유지하며 영역 DOM은 다시 만들지 않고 이동합니다. 공개 여부는 owner의 실제 PUBLIC 라우트와 일치해야 합니다.

카탈로그와 초안은 ADMIN 전용입니다. 익명 `GET /api/web/page-publications/{pageId}`는 선언된 공개 페이지의 렌더링 데이터만 반환하고 초안, schema, 속성 편집 URL은 노출하지 않습니다. 실제 페이지는 경량 runtime과 필요한 구성 요소만 불러오며 GrapesJS는 불러오지 않습니다. 문서는 editor owner의 runtime data 디렉터리 안 `pages`에 저장되며 JAR 자원이나 호스트 설정을 수정하지 않습니다.

형식 1은 제한된 GrapesJS project JSON, 제목, 인스턴스 설정과 연결을 저장합니다. HTML/CSS는 파생 결과입니다. 문서당 1 MiB, 노드 512개, 중첩 24단계, 연결 256개, 제목 256자까지 허용합니다. 상태 파일은 초안, 공개 버전, 이전 공개 버전을 보존합니다. revision으로 동시 저장을 검사하고 같은 디렉터리에서 원자적으로 교체합니다. 레이아웃과 글자 스타일을 지원하며 스크립트, 임의 HTML, 외부 자원 URL과 CSS import는 거부합니다.

## 독립 플러그인 검증 실행

Java 17, Maven, Python, Node.js와 설치된 Playwright/Chromium을 사용합니다. `PAGE_EDITOR_PLAYWRIGHT`에 Playwright `index.mjs`의 절대 경로를 지정하고 필요하면 `PAGE_EDITOR_CHROMIUM`에 브라우저 실행 파일을 지정하세요.

```powershell
mvn -pl pixivdownload-plugin-page-editor,pixivdownload-app,pixivdownload-sdk-tools,pixivdownload-plugin-download-workbench -am package '-DskipTests' '-Dexec.skip=true'
node scripts/ci/page-editor-acceptance.mjs target/page-editor-acceptance
```

첫 명령은 대상 산출물만 빌드합니다. 단위 테스트는 별도로 실행하세요. 둘째 명령에는 새 출력 디렉터리가 필요합니다. JAR을 고정한 후 예제를 별도로 생성·빌드하고 실제 설치 진입점, 브라우저, 수명 주기 및 고정 hash를 검증합니다. 설정과 DB는 격리되며 실제 사용자 데이터를 변경하지 않습니다. 배포 패키지나 CAPTCHA 검증을 대신하지 않습니다.

고정된 GrapesJS Core 파일, 라이선스와 hash는 `web/vendor/grapesjs/vendor.json`에 기록됩니다. 기본 CDN 아이콘과 telemetry는 비활성화됩니다. 구성 요소의 업무 네트워크 접근은 해당 플러그인이 선언합니다.

## 공식 CAPTCHA 플러그인

공식 `captcha` 플러그인은 Turnstile, ALTCHA SHA-256, ALTCHA PBKDF2 컴포넌트를 제공합니다. 설치, 폼 연결, 키 설정, 로컬 테스트와 문제 해결은 [작업 인증 플러그인](/ko/captcha)을 참고하세요.

폼 소유자는 `OperationGuard.prepare(operation, subject, context)`로 챌린지를 가져오며 이 호출은 업무를 실행하지 않습니다. 브라우저에서는 `createOperationTarget(operation, onChange, prepareRequest)`에 폼 자체의 준비 요청을 등록합니다. 인증 컴포넌트는 `context.interaction.prepare()`를 호출하고 챌린지를 받아 증명을 반환합니다. `target.ready()`가 true일 때만 폼이 `target.request()`로 제출 데이터를 가져올 수 있습니다. 제출 엔드포인트는 같은 소유자의 `OperationGuard.execute(...)`를 호출하고 검증 후 콜백에서 업무를 실행해야 합니다. 준비와 제출에는 같은 주체와 컨텍스트를 사용하세요. 입력 변경 시 `reset()`, 인증 무효화 시 `invalidate(id)`를 호출하면 증명을 지우고 대기 중인 준비 요청을 취소합니다. 준비에는 검증에 필요한 비민감 컨텍스트만 전송하며 인증 컴포넌트는 비밀번호나 전체 업무 요청을 읽지 않습니다.
