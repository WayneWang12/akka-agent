name := "akka-stream-dubbo-mesh"

version := "0.1"

scalaVersion := "2.12.6"


libraryDependencies ++= Seq(
  "com.alibaba" % "fastjson" % "1.2.46",
  "org.scalatest" %% "scalatest" % "3.0.5",
  "com.typesafe.akka" %% "akka-http" % "10.1.1",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.1.1",
  "com.typesafe.akka" %% "akka-stream" % "2.5.12",
  "com.typesafe.akka" %% "akka-remote" % "2.5.12"
)