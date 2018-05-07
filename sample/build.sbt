name := "akka-sample-remote-scala"

scalaVersion := "2.12.6"

libraryDependencies ++= Seq(
  "com.alibaba" % "fastjson" % "1.2.46",
  "com.typesafe.akka" %% "akka-stream" % "2.5.12",
  "com.typesafe.akka" %% "akka-remote" % "2.5.12",
  "com.coreos" % "jetcd-core" % "0.0.2"
)

