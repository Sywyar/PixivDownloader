name := "sdk-consumer"
version := "1.0"
autoScalaLibrary := false
crossPaths := false
Compile / unmanagedSourceDirectories := Seq(baseDirectory.value.getParentFile / "src" / "main" / "java")
javacOptions ++= Seq("--release", "17", "-encoding", "UTF-8")
libraryDependencies += "io.github.sywyar.pixivdownloader" % "pixivdownload-sdk" % sys.env("SDK_VERSION") % Provided

val verifySdk = taskKey[Unit]("Compile the SDK consumer and reject host dependencies at runtime")
verifySdk := {
  val artifact = (Compile / packageBin).value
  require(artifact.isFile, "Consumer JAR is missing")
  require((Runtime / externalDependencyClasspath).value.isEmpty, "SDK dependencies leaked into the runtime classpath")
  IO.write(target.value / "sdk-classpath.txt", (Compile / dependencyClasspath).value.map(_.data.getAbsolutePath).mkString(java.io.File.pathSeparator))
}
