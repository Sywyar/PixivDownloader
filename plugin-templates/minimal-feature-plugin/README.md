# Minimal Feature Plugin Template

This standalone Maven project is a template for a thin external PF4J plugin running in an isolated worker (`declarative-process` + `hot-reload`). It demonstrates a root `plugin.properties`, a PF4J entry point, `PixivPluginProvider`, `PixivFeaturePlugin`, and declarative route, static-resource, and i18n contributions without a Spring child context. For template reuse and licensing, see the [Plugin Linking Exception](https://github.com/Sywyar/PixivDownloader/blob/master/PLUGIN-LINKING-EXCEPTION.txt).

## 1. Build and Run

1. Install JDK 17+, Maven, and Node.js (`java`, `mvn`, and `node` on `PATH`).
2. Run `mvn clean verify` in this directory and confirm `target/example-minimal-plugin-0.1.0.jar` is produced.
3. Upload the JAR through Plugin Management in a compatible host, confirm the plugin starts under `declarative-process`, and open `/example-minimal.html` as an administrator.

The POM declares a single `provided` dependency on `pixivdownload-sdk` (plus JUnit for tests); never bundle SDK or host-provided libraries into the output JAR. Keep the Git-tracked `.pixivdownloader-plugin-project` marker (`pixivdownloader-plugin-project-v1` + LF) in the project root (excluded from the JAR).

## 2. Customize the Template

Replace these identifiers consistently across code, resources, descriptors, and tests:

| Template value | Replace with |
| --- | --- |
| `example-minimal-plugin` | Your Maven `artifactId` |
| `example-minimal` | Globally unique lowercase plugin ID, URL prefix, and i18n namespace |
| `com.example.pixivdownload.minimal` | Your Java package and matching directory structure |
| `ExampleMinimal*` | Your Java class prefix |
| `0.1.0` | Your artifact and `plugin.version` |
| `plugin.requires` | `MAJOR.MINOR` for a stable SDK, or `=FULL_SDK_VERSION` (e.g., `plugin.requires==7.2.3-rc.4`) for a prerelease SDK |
| `plugin.provider=Example Developer` | Your publisher name |

## 3. Execution Mode and Compatibility

- Keep the compile dependency and `plugin.requires` synchronized.
- This template declares `declarative-process`, `hot-reload`, `pixiv.risk-signals=` (empty), and no `pixiv.configuration-classes`. In-process Spring configuration classes, controllers, queues, and scheduled executors are not supported in `declarative-process`.
- The worker runs in a separate JVM under the same operating-system account; process separation is not an OS security sandbox.
