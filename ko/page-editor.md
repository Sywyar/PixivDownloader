# 페이지 편집기 및 플러그인 컴포넌트

페이지 편집기(`page-editor` 플러그인, SDK 1.4+ 필요)는 로그인 페이지 등 지원되는 페이지의 레이아웃, 반응형 스타일, 사용자 정의 컴포넌트와 양식 검증 컴포넌트를 시각적으로 구성하는 도구입니다. 플러그인을 설치하고 활성화한 후 관리자로 탐색 메뉴의 '페이지 편집기'에 들어갑니다.

## 페이지 편집, 게시 및 복구

1. 대상 페이지를 선택하고 왼쪽의 '구성 요소 추가'에서 레이아웃, 콘텐츠 또는 플러그인 컴포넌트를 캔버스로 끌어 넣습니다. 키보드로는 컴포넌트에 포커스를 두고 `Enter` / `Space`로 상세 정보를 연 다음 '페이지에 추가'를 선택할 수 있습니다.
2. 왼쪽의 '구조'나 캔버스에서 인스턴스를 선택하고 오른쪽에서 속성, 레이아웃과 외관을 조정합니다. 컨테이너 중첩, 위/아래 이동, 부모 밖으로 이동, 삭제와 좁은 뷰포트용 반응형 재정의를 지원합니다.
3. '초안 저장' 또는 '미리 보기'를 누릅니다. 미리보기는 실제 페이지 스타일을 사용하지만 로그인이나 업무 쓰기 요청은 제출하지 않습니다. '편집으로 돌아가기'는 저장하지 않은 변경을 유지합니다.
4. '게시'를 눌러 정식 페이지를 교체합니다. 공개 페이지의 컴포넌트 설정과 속성 기본값도 공개되므로 비밀번호나 토큰을 입력하지 마세요.
5. 상단의 '더 보기' 메뉴에는 '초안 다시 불러오기', '이전 게시 버전 복원'(정식 버전과 초안을 모두 교체), '페이지 기본 레이아웃 복원'(저장하지 않은 캔버스만 교체)이 있습니다.

정식 페이지 실행과 작업 보호는 편집기를 열어 두지 않아도 유지됩니다. 정식 페이지의 '기본 레이아웃 사용'은 현재 브라우저 표시만 바꾸며 서버의 검증 요구는 지우지 않습니다. 없거나 버전이 호환되지 않는 플러그인 컴포넌트가 페이지에 있어도 초안은 저장할 수 있지만 해당 플러그인을 복구하거나 설정 마이그레이션을 완료해야 게시할 수 있습니다.

## 양식 검증 및 리소스 권한

페이지 owner가 선언한 양식 영역으로 CAPTCHA 컴포넌트를 끌어 넣으면 호환 대상이 하나인 경우 자동으로 연결합니다. 여러 대상이 있으면 오른쪽의 '보호 작업'에서 명시적으로 지정해야 합니다. 같은 양식에 검증 컴포넌트를 여러 개 연결하면 모든 정책을 완료해야 합니다.

게시 전에 영향받는 작업, 정책 변경과 컴포넌트에 필요한 외부 리소스 출처를 표시합니다. 게시를 확인하면 서버가 대상 작업의 새 요청을 일시 중지하고 진행 중인 동작이 끝날 때까지 기다린 후 페이지, 연결과 CSP 리소스 권한을 원자적으로 커밋합니다. `setup.login-verification-policies`의 정적 로그인 보호 요구도 유지합니다.

정식 게시 상태는 `data/web-publications/`, 등록 파일은 `config/web-publications.enrolled`에 저장됩니다. 캐시로 취급하여 삭제하지 마세요. 오프라인 복구가 필요하면 프로그램을 중지하고 배포 폴더에서 조회한 다음 후보 파일과 SHA-256을 확인하여 복구합니다.

```text
java -jar "<boot-jar>" --no-gui --web-publications
java -jar "<boot-jar>" --no-gui --web-publications --publication-restore=<candidate.json> --publication-sha256=<sha256>
```

## 머리글, 바닥글 및 사용자 정의 CSS

기본 레이아웃은 머리글, 본문(세로 방향으로 유연하게 늘어나 남은 공간을 채움)과 바닥글로 구성됩니다. 등록 번호 등의 정보를 추가하려면 '바닥글'을 선택하고 '텍스트'나 '링크'를 넣으세요. 로그인 카드의 제목, 사용자 이름, 비밀번호와 버튼은 제어되는 필수 영역입니다. 컨트롤 사이에 텍스트나 컴포넌트를 넣을 수 있지만 필수 영역을 삭제하거나 복제하거나 부모 영역 밖으로 옮길 수는 없습니다. '처음에 숨겨진 영역 표시'를 선택하면 초대 코드 카드를 편집할 수 있습니다.

컴포넌트를 선택하고 오른쪽의 '스타일 → 사용자 지정 CSS'에서 내장 CodeMirror로 속성 선언을 편집합니다. 줄 번호, 자동 완성, 오류 위치와 오른쪽 위 전체 화면 전환을 지원합니다. `Ctrl/Cmd+Enter` 또는 '스타일 적용'을 누르면 적용됩니다.

```css
padding: 16px;
border-radius: 8px;
```

- 속성값은 최대 128자이며 선택한 컴포넌트 루트 요소와 현재 캔버스 너비에 적용됩니다. 좁은 화면용 재정의를 별도로 설정할 수 있으며 비우고 적용하면 재정의를 제거합니다.
- 선택자, 주석, `@media` / `@import`, 외부 리소스 URL, `!important`, `display: none` 또는 사용자 정의 CSS 변수는 허용하지 않습니다. 저장, 미리보기나 게시 전에 적용하거나 '변경 취소'를 눌러야 합니다.
- 지원 속성: `display`, `flex-direction`, `flex-wrap`, `justify-content`, `align-items`, `gap`, `padding`(네 방향 포함), `margin`(네 방향 포함), `width`, `min-width`, `max-width`, `min-height`, `font-size`, `font-weight`, `line-height`, `text-align`, `color`, `background-color`, `border`, `border-color`, `border-width`, `border-style`, `border-radius`, `box-shadow`, `grid-template-columns`, `flex`, `order`.

## 사용자 정의 HTML / CSS / JavaScript 컴포넌트 라이브러리

'구성 요소 추가'에서 '새 컴포넌트' 또는 '컴포넌트 라이브러리 관리'를 누릅니다. 별도 탭으로 열리며 원래 페이지 초안에는 영향을 주지 않습니다.

1. '소스'에서 HTML 조각, CSS와 선택적 JavaScript 함수 본문을 편집합니다. '속성 정의'에서 인스턴스 필드를 추가합니다. 텍스트 `string`, 숫자 `number`, 스위치 `boolean`을 지원합니다.

   ```json
   {"title":{"type":"string","label":"Title","default":"Hello"}}
   ```

   예를 들어 HTML에 `<p></p>`, JavaScript에 `root.querySelector('p').textContent = context.properties.title;`을 입력합니다. 함수는 `root`와 `properties`, `mode`를 포함한 `context`를 받으며 `update(context)`와 `destroy()`를 반환할 수 있습니다.
2. '미리보기 실행'으로 결과를 확인한 후 '게시'를 눌러 불변 버전으로 저장하고 컴포넌트 패널에 추가합니다.
3. 컴포넌트를 페이지로 끌어 넣고 페이지를 게시합니다. 이후 컴포넌트의 새 버전을 게시해도 이미 게시된 페이지는 자동 변경되지 않습니다. 페이지 인스턴스 속성에서 새 버전을 선택하고 '선택한 버전 사용 및 속성 초기화'를 누른 후 페이지를 다시 게시해야 합니다.
4. '컴포넌트 철회'는 기존 인스턴스 렌더링을 중지하며 소스와 이전 버전은 유지합니다.

사용자 정의 컴포넌트는 `allow-same-origin`이 없는 샌드박스 iframe에서 실행됩니다. CSP는 `fetch`/`XHR`, 외부 스크립트와 외부 스타일을 금지합니다. HTML 안에서는 `<script>`, `<style>`, `<form>`, 임베디드 페이지, 외부 리소스와 인라인 이벤트 속성을 금지합니다. 컴포넌트 라이브러리는 `data/page-editor/components/`에 저장됩니다. 소스 하나당 최대 1 MiB, 컴포넌트 최대 128개, 컴포넌트당 최대 4,096개 버전과 64개 속성입니다.

## 서드파티 플러그인 컴포넌트 개발 및 페이지 연결

플러그인은 공개 계약으로 컴포넌트와 편집 가능한 페이지를 제공할 수 있으며 호스트나 편집기 소스를 수정할 필요가 없습니다. 전체 검증 예시는 주 저장소의 [`page-components` fixture](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-app/src/test/resources/page-components)를 참고하세요.

| 확장점 | 책임 및 제약 |
| --- | --- |
| 업무 계약 및 `sdk-tools` | 독립 API 패키지에서 작업과 설정 record를 선언하고 JSON Schema, 브라우저 클라이언트와 Java HTTP adapter 생성 |
| `PageContribution.components()` / `componentCategories()` | `PageComponent`(안정적인 `owner:type`, 양의 정수 설정 버전, 모듈 URL, schema/defaults, `publicRenderable`, 포트)와 `owner:local-id` 분류 선언. `validate` 및 선택적 `migrate` 구현 |
| 렌더링 및 속성 편집 모듈 | `mount(root, context)` 및 복잡한 속성용 `mountProperties(root, context)` 내보내기. `context.fetch`, `context.signal`, `context.onCleanup` 사용. GrapesJS 객체를 노출하지 않으며 `edit`/`preview` 모드의 직접 업무 쓰기 요청 금지 |
| `PageContribution.pages()` | `EditablePage`(페이지 ID, 경로, `regions`, `defaultProject`, `previewModuleUrl`, 동일 owner CSS 최대 16개) 선언. 프런트엔드는 `/js/pixiv-pages.mjs`의 `attachPage` 호출. 코어 `login/login-page.mjs` 참고 |

```js
import {createClient} from './generated/my.contract.mjs';

export function mount(root, context) {
    const client = createClient(context.fetch);
    const button = document.createElement('button');
    button.textContent = context.t('run');
    button.disabled = context.mode !== 'display';
    context.on(button, 'click', async () => {
        try {
            const result = await client.run(context.properties, {signal: context.signal});
            if (!context.signal.aborted) context.emit('result', result);
        } catch (error) {
            if (!context.signal.aborted) root.dataset.failed = 'true';
        }
    });
    root.append(button);
    return {
        update(properties) { /* 표시 갱신만 하고 쓰기 작업은 실행하지 않음 */ },
        destroy() { root.replaceChildren(); }
    };
}
```

페이지 문서당 최대 1 MiB, 노드 512개, 중첩 24단계, 포트 연결 256개, 제목 256자입니다. 독립 브라우저 및 수명 주기 검증 명령:

```powershell
mvn -pl pixivdownload-plugin-page-editor,pixivdownload-app,pixivdownload-sdk-tools,pixivdownload-plugin-download-workbench -am package '-DskipTests' '-Dexec.skip=true'
node scripts/ci/page-editor-acceptance.mjs target/page-editor-acceptance
```

## 공식 CAPTCHA 플러그인

공식 `captcha` 플러그인은 Turnstile, reCAPTCHA v2, ALTCHA SHA-256, ALTCHA PBKDF2, 문자 입력과 슬라이더 퍼즐의 여섯 가지 검증 컴포넌트를 제공합니다. 설치, 양식 연결, 키 설정, 공식 테스트 키와 문제 해결은 [작업 CAPTCHA](/ko/captcha)를 참고하세요.

관리 대상 페이지는 `attachPage`로 공통 제출 런타임을 로드합니다. 선언된 작업을 선택하거나 작업 콜백에서 양식 ID, 제출 버튼 ID, 컴포넌트 영역과 대상 endpoint를 설정합니다. 기본 action/method와 submitter 재정의 속성으로 후보를 만들고 서버 디렉터리와 대조합니다. JavaScript 요청은 실제 endpoint를 명시적으로 선택하고, 한 양식의 여러 작업을 각각 설정하세요. 컴포넌트를 배치하고 보호 대상을 확인한 뒤 출처, 대상과 권한을 검토하여 게시합니다.

공통 런타임은 관리 대상의 기본 제출, fetch(Request 포함), 비동기 XHR을 처리하면서 업무 본문과 응답을 보존합니다. 컨트롤러가 실행되지 않은 경우에만 상호작용을 마친 뒤 전송합니다. 동기 XHR과 안전하게 추출·재사용할 수 없는 본문은 명시적으로 거부하고, 설정되지 않은 교차 출처 요청은 처리 대상에서 제외합니다. 페이지 폴백, 컴포넌트 부재나 편집기 중지에도 서버 보호는 유지됩니다. 공유 작업의 원자적 게시·복원은 관련 초안 또는 각 페이지의 previous를 명시적으로 선택해야 합니다. 선택하지 않은 초안은 보존되며 리비전이나 권한이 바뀌면 이전 확인은 무효가 됩니다.
