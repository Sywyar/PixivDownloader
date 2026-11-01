# 독립 CAPTCHA 플러그인

[简体中文](README.md) | [English](README_en.md) | [日本語](README_ja.md) | [한국어](README_ko.md) | [繁體中文](README_zh-Hant.md)

하나의 플러그인에서 Turnstile 온라인 검증과 ALTCHA SHA-256/PBKDF2 오프라인 검증을 제공합니다. SDK 1.4의 작업 검증 및 페이지 연결을 사용합니다.

JDK 17, Maven, Node.js/npm과 해석 가능한 정확한 SDK 의존성이 필요합니다. 이 디렉터리에서 실행하세요.

```text
npm ci
mvn package
```

`target/captcha-example-0.1.0.jar`를 설치하고 [페이지 편집기 가이드](https://sywyar.github.io/PixivDownloader/#/ko/page-editor)에 따라 자격 증명을 설정하고 폼에 연결한 뒤 게시하세요. 공식 ALTCHA Worker와 라이선스는 빌드 시 포함됩니다. 공식 테스트 설정은 기본적으로 꺼져 있습니다. 운영 사이트에는 자신의 Turnstile 자격 증명을 사용하세요.
