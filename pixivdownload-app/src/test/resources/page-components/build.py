"""Build an independent component and its business contract against frozen SDK artifacts."""
import hashlib
import os
import pathlib
import shutil
import subprocess
import sys
import zipfile

root = pathlib.Path(sys.argv[1]).resolve()
host = root / "host"
sources = pathlib.Path(__file__).resolve().parent
work = root / "fixture"
work.mkdir()
sdk = list((root / "sdk").glob("*.jar"))
libraries = list((host / "BOOT-INF/lib").glob("*.jar"))
classpath = os.pathsep.join(str(p) for p in sdk + libraries)

def run(*args):
    subprocess.run(args, check=True)

def compile_java(source, output, extra=""):
    output.mkdir(parents=True, exist_ok=True)
    run("javac", "-proc:none", "-encoding", "UTF-8", "-cp", classpath + extra, "-d", str(output), str(source))

def jar(path, classes, descriptor=None, extra=None):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for file in classes.rglob("*"):
            if file.is_file():
                archive.write(file, file.relative_to(classes).as_posix())
        if descriptor:
            archive.writestr("plugin.properties", descriptor)
        for name, data in (extra or {}).items():
            archive.writestr(name, data)

version_file = next((root / "sdk").glob("pixivdownload-sdk-info-*.jar"))
with zipfile.ZipFile(version_file) as archive:
    metadata = archive.read("META-INF/pixivdownload-sdk.properties").decode("utf-8")
version = next(line.split("=", 1)[1].strip() for line in metadata.splitlines() if line.startswith("version="))
requires = ".".join(version.split(".")[:2]) if "-" not in version else "=" + version

def props(owner, entry, tail):
    return ("plugin.id=" + owner + "\nplugin.version=0.1.0\nplugin.requires=" + requires
            + "\nplugin.class=" + entry + "\nplugin.provider=Page fixture\nplugin.description=Independent component example"
            + "\npixiv.kind=feature\npixiv.execution-mode=host-process-full-trust\n" + tail)

project = work / "contract-source"
source = project / "src/main/java/example/pages/contract/Checklist.java"
source.parent.mkdir(parents=True)
shutil.copyfile(sources / "Checklist.java", source)
run("java", "-jar", str(root / "sdk-tools.jar"), "schema", str(project), classpath)
api_classes = project / "target/classes"
compile_java(source, api_classes)
api_jar = work / "checklist-api.jar"
jar(api_jar, api_classes)
compile_java(sources / "Contract.java", work / "wrapper")
api_bytes = api_jar.read_bytes()
jar(work / "page-checklist-contract.jar", work / "wrapper",
    props("page-checklist-contract", "example.pages.wrapper.Contract",
          "pixiv.lifecycle-policy=process-restart\npixiv.contract-api=lib/checklist-api.jar\npixiv.contract-sha256="
          + hashlib.sha256(api_bytes).hexdigest() + "\n"),
    {"lib/checklist-api.jar": api_bytes})

plugin_project = work / "component"
plugin_project.mkdir()
run("java", "-jar", str(root / "sdk-tools.jar"), "remote", str(plugin_project),
    classpath + os.pathsep + str(api_jar), "example.pages.contract.Checklist")
classes = plugin_project / "target/classes"
compile_java(sources / "ChecklistPlugin.java", classes, os.pathsep + str(api_jar) + os.pathsep + str(classes))
render = classes / "web/render"
manage = classes / "web/manage"
render.mkdir(parents=True, exist_ok=True)
manage.mkdir(parents=True, exist_ok=True)
shutil.move(str(classes / "web/generated"), str(render / "generated"))
shutil.copyfile(sources / "component.mjs", render / "component.mjs")
for name in ["slots.html", "slots-host.js", "slot-fixture.js", "slots.css", "official-slots.html", "official-slots-host.js"]:
    shutil.copyfile(sources / name, render / name)
shutil.copyfile(sources / "properties.mjs", manage / "properties.mjs")
bundles = classes / "i18n/web"
bundles.mkdir(parents=True)
translations = [
    ("plugin.name", "示例清单", "Example checklist"), ("plugin.summary", "页面组件独立接入示例", "Independent page component example"),
    ("component", "清单", "Checklist"), ("verify", "校验清单", "Verify checklist"), ("failed", "校验失败", "Verification failed"),
    ("title", "清单标题", "Checklist title"), ("item", "条目", "Item"), ("checked", "已完成", "Checked"),
    ("remove", "删除条目", "Remove item"), ("add", "添加条目", "Add item")]
for suffix, index in [("", 1), ("_en", 2)]:
    (bundles / ("page-checklist" + suffix + ".properties")).write_text(
        "\n".join(row[0] + "=" + row[index] for row in translations) + "\n", encoding="utf-8")
jar(work / "page-checklist.jar", classes,
    props("page-checklist", "example.pages.plugin.ChecklistPlugin",
          "pixiv.lifecycle-policy=hot-reload\nplugin.dependencies=page-checklist-contract@0.1.0\n"
          "pixiv.configuration-classes=example.pages.plugin.ChecklistPlugin$Configuration\n"
          "pixiv.display-namespace=page-checklist\npixiv.display-name-key=plugin.name\npixiv.description-key=plugin.summary\n"))
print("PAGE_COMPONENT_INDEPENDENT_PLUGIN_BUILT")
