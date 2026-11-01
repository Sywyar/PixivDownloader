# 작업 인증 플러그인

공식 `captcha` 플러그인은 Turnstile, ALTCHA SHA-256, ALTCHA PBKDF2 폼 구성 요소를 제공합니다. 필요할 때 설치하는 플러그인으로, 일반 배포판에는 기본 설치되지 않으며 전체 오프라인 패키지에는 포함됩니다. 호환 폼에 배치하고 게시하면 서버가 해당 인증을 필수로 검증합니다.

CAPTCHA는 자동 제출 비용을 높입니다. 네트워크 DDoS 방어, 역방향 프록시의 요청 제한, 접근 제어를 대신하지는 않습니다. 외부 연결은 [네트워크 액세스 및 타사 서비스](/ko/network-access)를 참고하세요.

## 인증 방식 선택

| 방식 | 사용자 동작 | 처리 위치 | 준비 사항 |
| --- | --- | --- | --- |
| Turnstile 온라인 인증 | Managed 모드에서 인증 시작 후 Cloudflare 위젯이 추가 조작 필요 여부를 결정 | 브라우저에서 Cloudflare를 로드하고 서버에서 Cloudflare에 토큰 검증 요청 | Cloudflare 계정, Sitekey, Secret key, 허용 호스트 이름 |
| ALTCHA SHA-256 | 구성 요소 이름은 ALTCHA 오프라인 인증. 클릭 후 계산하고 인증 완료 표시 | 브라우저가 SHA-256 작업 증명을 계산하고 로컬 서비스가 챌린지를 발급·검증 | 처음 활성화할 때 생성하는 로컬 서명 키. 타사 계정 불필요 |
| ALTCHA PBKDF2 | ALTCHA PBKDF2 오프라인 인증. 조작은 SHA-256과 동일 | 브라우저가 PBKDF2/SHA-256 작업 증명을 계산하고 로컬 서비스가 검증 | SHA-256과 같은 로컬 서명 키. 타사 계정 불필요 |

두 ALTCHA 방식 모두 이미지 식별이나 문자 입력을 요구하지 않습니다. 오프라인은 타사 인증 서비스에 의존하지 않는다는 뜻이며, 브라우저는 PixivDownloader 인스턴스에 연결되어야 합니다. 계산 시간은 알고리즘, 매개변수, 기기에 따라 달라집니다. PBKDF2라는 이름만으로 속도나 보호 수준이 더 높다고 판단할 수 없습니다. [ALTCHA 작업 증명 설명](https://altcha.org/docs/integration/proof-of-work-captcha/)

일반적으로 폼마다 하나를 선택합니다. **여러 구성 요소를 넣으면 모두 인증해야 합니다.** 선택형 옵션이 아니며 Turnstile이 작동하지 않아도 ALTCHA로 자동 전환하지 않습니다.

## 설치 및 폼 연결

1. [플러그인 관리와 마켓](/ko/plugin-management)에서 작업 인증과 페이지 편집기를 설치하고 활성화합니다.
2. 오프라인 방식은 자동 생성한 키를 그대로 사용합니다. Turnstile은 아래 설정을 먼저 완료합니다.
3. [페이지 편집기](/ko/page-editor)에서 로그인 등 호환 페이지를 선택하고 CAPTCHA 분류의 구성 요소를 폼으로 끌어 놓습니다. 보호할 작업이 해당 폼에 연결되었는지 확인하고, 폼 밖에 배치했거나 연결이 모호하면 게시 전에 수정합니다.
4. 요청하는 리소스 권한을 확인하고 게시합니다. 초안 저장만으로는 실제 페이지의 인증 요구가 바뀌지 않습니다.
5. 로그인하지 않은 창에서 게시된 페이지를 엽니다. 사용자 이름과 비밀번호를 입력하고 인증 시작을 누른 뒤, 모든 인증이 완료되면 로그인합니다.

편집기와 미리보기에서는 실제 인증을 실행하지 않습니다. 실제 페이지는 증명이 완성되기 전에 로그인 요청을 보내지 않으며, 서버도 비밀번호 확인과 세션 생성 전에 증명을 검증합니다. 사용자 이름, 비밀번호, 로그인 유지 옵션을 바꾸면 증명이 지워집니다. 비밀번호 오류, 인증 실패, 만료 후에는 다시 인증해야 합니다.

플러그인은 핫 로드를 지원하므로 일반적인 설치와 활성화 후 페이지를 새로 고치면 됩니다. 개발 환경에서 이미 로드된 Java 클래스나 호스트 코드를 갱신했다면 개발 인스턴스를 재시작한 뒤 새로 고쳐야 합니다.

## 오프라인 인증 설정

두 방식은 ALTCHA 서명 키를 공유합니다. 처음 활성화할 때 32바이트 난수를 생성하고 호스트가 암호화하여 인스턴스의 `config/credentials/captcha.properties`에 저장합니다. 설정 루트 디렉터리를 변경했다면 그 아래에 저장됩니다. 재활성화나 재시작 때 기존 키를 재사용하며 자동 만료가 없어 보통 변경할 필요가 없습니다.

직접 교체할 때는 32자 이상이어야 합니다. 비밀번호 입력란을 비워 저장하면 기존 값을 유지합니다. 저장된 자격 증명을 지우면 실행 중인 오프라인 인증은 작업을 거부하고, 다음 플러그인 재활성화 때 새 키를 생성합니다. 잘못된 기존 값, 저장소 손상, 쓰기 실패를 임시 키로 우회하지 않습니다.

Worker는 플러그인에 포함되어 외부 CDN이 필요 없습니다. 계산에 Web Crypto를 사용하므로 HTTPS나 브라우저가 안전하게 취급하는 로컬 출처로 접속하세요. LAN IP의 일반 HTTP에서는 API를 사용할 수 없을 수 있습니다. [Web Crypto 보안 컨텍스트 요구 사항](https://developer.mozilla.org/en-US/docs/Web/API/SubtleCrypto)

## Turnstile 키 발급 및 설정

### Sitekey와 Secret key 발급

1. [Cloudflare 대시보드](https://dash.cloudflare.com/)에 로그인하고 **Turnstile → Add widget**을 엽니다.
2. `PixivDownloader`처럼 위젯 이름을 입력합니다.
3. Hostname management에 실제 접속 도메인(예: `download.example.com`)을 추가합니다.
4. **Managed**를 선택하면 Cloudflare가 추가 사용자 조작 여부를 판단합니다.
5. Create를 누르고 같은 위젯의 Sitekey와 Secret key를 복사합니다. Sitekey는 공개 식별자지만 서버용 Secret key는 비공개로 보관해야 합니다. [공식 생성 안내](https://developers.cloudflare.com/turnstile/get-started/widget-management/dashboard/)

웹사이트가 Cloudflare 프록시를 사용할 필요는 없습니다. 브라우저 위젯과 서버 검증은 Cloudflare에 연결할 수 있어야 합니다. [공식 연동 안내](https://developers.cloudflare.com/turnstile/get-started/)

### 프로그램에 입력

설정 → 작업 인증을 엽니다.

| 설정 | 입력 값 |
| --- | --- |
| Turnstile 사이트 키 | 위젯의 Sitekey |
| Turnstile 비밀 키 | 같은 위젯의 Secret key |
| 예상 Turnstile 호스트 이름 | 실제 접속 호스트 이름(예: `download.example.com`) |
| 공식 테스트 설정 허용 | 직접 발급한 키를 사용할 때 끄기 |

호스트 이름에는 프로토콜, 포트, 경로를 넣지 않습니다. `https://download.example.com:6999/login.html`을 입력하지 마세요. Cloudflare의 허용 호스트와 프로그램의 예상 호스트를 모두 설정해야 합니다. 플러그인은 검증 응답의 호스트 이름이 정확히 일치해야 통과시킵니다. [호스트 이름 요구 사항](https://developers.cloudflare.com/turnstile/additional-configuration/hostname-management/)

저장 후 Turnstile 구성 요소를 폼에 넣고 게시합니다. 사이트 키, 예상 호스트, 테스트 모드를 바꾸면 기존 페이지도 다시 게시해야 합니다. 비밀 키만 교체하면 기존 챌린지가 무효화되어 새 인증이 필요합니다. 일반 설정은 `config/plugins/captcha.properties`, 비밀 값은 암호화된 자격 증명 파일에 저장하며 페이지 문서에는 저장하지 않습니다.

### 직접 발급한 키로 로컬 테스트

개발용 위젯을 따로 만들고 브라우저 주소에 맞춰 입력합니다.

| 브라우저 주소 | Cloudflare 허용 호스트 | 플러그인 예상 호스트 |
| --- | --- | --- |
| `http://localhost:6999` | `localhost` | `localhost` |
| `http://127.0.0.1:6999` | `127.0.0.1` | `127.0.0.1` |
| `https://download.example.com` | `download.example.com` | `download.example.com` |

포트 변경은 호스트 이름에 영향을 주지 않습니다. `localhost`와 `127.0.0.1`을 전환하면 설정을 바꾸고 다시 게시해야 합니다. **로컬 실행 때문에 공식 테스트 설정 허용을 켤 필요는 없습니다.** Cloudflare는 개발용 로컬 호스트 이름을 허용하지만 운영용 위젯에는 허용하지 않도록 권장합니다. [공식 로컬 테스트 안내](https://developers.cloudflare.com/turnstile/troubleshooting/testing/)

### 공식 테스트 키로 연동 확인

구성 요소와 제출 흐름만 확인하려면 Cloudflare가 공개한 항상 성공하는 테스트 값을 사용할 수 있습니다.

| 설정 | 테스트 값 |
| --- | --- |
| Turnstile 사이트 키 | `1x00000000000000000000AA` |
| Turnstile 비밀 키 | `1x0000000000000000000000000000000AA` |
| 예상 Turnstile 호스트 이름 | `example.com`(이 플러그인의 공식 테스트 규약) |
| 공식 테스트 설정 허용 | 켜기 |

저장하고 다시 게시한 뒤 테스트합니다. 여기서 `example.com`은 테스트 응답을 검증하는 값입니다. 해당 도메인을 소유하거나 방문할 필요가 없으며 페이지는 `localhost`에서 열 수 있습니다. 테스트 사이트 키에는 테스트 비밀 키가 필요합니다. 값의 출처는 [Cloudflare 테스트 문서](https://developers.cloudflare.com/turnstile/troubleshooting/testing/)이며 **실제 봇 방어 기능을 제공하지 않습니다**. 운영 전에 본인 키와 실제 호스트로 변경하고 테스트 설정을 끈 다음 다시 게시하세요.

## 인증이 되지 않을 때

| 현상 또는 로그 | 확인 및 조치 |
| --- | --- |
| 편집기에는 있지만 실제 페이지에 없음 | 초안 저장뿐 아니라 게시가 성공했는지 확인하고 새로 고침 |
| `CAPTCHA_CREDENTIAL_MISSING` / `CAPTCHA_CREDENTIAL_INVALID` | 로그의 설정 키를 확인. 비밀 값은 로그나 문제 보고에 포함하지 않기 |
| `CAPTCHA_CONFIG_INVALID` | 지목한 사이트 키, 호스트, 테스트 모드와 키 조합 확인 |
| `PUBLISHED_POLICY_CHANGED` | 설정을 확인하고 해당 페이지 다시 게시 |
| `CAPTCHA_REMOTE_UNAVAILABLE` 또는 위젯 로드 실패 | 브라우저와 서버의 Cloudflare 연결, 프록시, 차단 규칙 확인 |
| `HOSTNAME_MISMATCH` | 브라우저 주소, Cloudflare 허용 호스트, 예상 호스트 비교 |
| ALTCHA 추가 후에도 정책 사용 불가 | 설정하지 않은 Turnstile도 필수인지 확인. 오프라인만 필요하면 제거 후 다시 게시 |
| 완료 후 다시 인증 요구 | 입력 변경, 잘못된 비밀번호 제출, 만료, 플러그인 설정 변경 확인 |

필수 로그인 인증 정책을 사용할 수 없다는 메시지는 요약 오류입니다. 앞선 로그에서 구체적인 이유와 설정 키를 확인하세요. 플러그인을 비활성화하거나 제거해도 게시된 요구 사항은 남아 보호된 작업을 차단합니다. 요구 사항을 없애려면 페이지를 수정하고 다시 게시해야 합니다.
