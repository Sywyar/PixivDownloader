# CAPTCHA 플러그인의 독립 SDK 빌드

[简体中文](README.md) | [English](README_en.md) | [日本語](README_ja.md) | [한국어](README_ko.md) | [繁體中文](README_zh-Hant.md)

이 프로젝트는 호스트 Maven parent를 상속하지 않고 공개 SDK 1.4와 CAPTCHA 라이브러리만으로 `pixivdownload-plugin-captcha`의 소스와 테스트를 컴파일합니다. 하나의 플러그인이 Turnstile, reCAPTCHA v2 온라인 인증과 ALTCHA SHA-256, PBKDF2, Hutool 문자 입력, Tianai 슬라이더 퍼즐 오프라인 인증을 제공합니다.

전체 소스 체크아웃, JDK 17, Maven, Node.js/npm과 지정 버전의 SDK 의존성이 필요합니다. 이 디렉터리에서 실행하세요:

```text
mvn package
```

빌드 시 잠금 파일의 npm 의존성을 설치하고 공식 ALTCHA Worker와 라이선스를 포함합니다. 결과물은 `target/captcha-sdk-consumer-1.0.0.jar`이며 플러그인 ID는 `captcha`입니다. 공식 모듈과 같은 플러그인이므로 둘 중 하나만 설치하세요. 처음 활성화하면 로컬 서명 키를 생성하여 암호화해 저장합니다. 여섯 구성 요소의 설치, 폼 연결, 설정은 [CAPTCHA 플러그인 가이드](https://sywyar.github.io/PixivDownloader/#/ko/captcha)를 참조하세요.
