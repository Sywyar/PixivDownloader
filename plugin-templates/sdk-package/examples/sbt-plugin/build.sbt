import scala.sys.process.Process

name := "example-minimal-plugin"
organization := "com.example.pixivdownload"
version := "0.1.0"
autoScalaLibrary := false
crossPaths := false
Compile / javacOptions ++= Seq("--release", "17", "-encoding", "UTF-8", "-g")
libraryDependencies += "io.github.sywyar.pixivdownloader" % "pixivdownload-sdk" % "@SDK_VERSION@" % Provided

lazy val checkJavaScript = taskKey[Unit]("检查插件 JavaScript")
lazy val runPlugin = taskKey[Unit]("构建插件并运行 SDK 宿主")
lazy val debugPlugin = taskKey[Unit]("构建插件并调试实际执行进程")

checkJavaScript := {
  val result = Process(Seq("node", "--check", "src/main/resources/static/example-minimal/example-minimal-init.js"), baseDirectory.value).!
  if (result != 0) sys.error("JavaScript check failed")
}

// 构建失败时不进入此函数；运行与调试共用 SDK 工具的准备、准入和进程管理。
def sdkCommand(project: File, action: String, artifact: File): Unit = {
  val java = file(sys.props("java.home")) / "bin" / "java"
  val args = Seq(java.getAbsolutePath, "-Dfile.encoding=UTF-8", "-jar",
    (project / ".." / ".." / "tools" / "sdk-tools.jar").getCanonicalPath,
    action, project.getCanonicalPath, artifact.getAbsolutePath)
  val child = Process(args, project).run()
  try {
    val result = child.exitValue()
    if (result != 0) sys.error(s"SDK command failed: $result")
  } finally child.destroy()
}

runPlugin := {
  checkJavaScript.value
  sdkCommand(baseDirectory.value, "run", (Compile / packageBin).value)
}
debugPlugin := {
  checkJavaScript.value
  sdkCommand(baseDirectory.value, "debug", (Compile / packageBin).value)
}
