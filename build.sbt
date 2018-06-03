
val nettyVersion = "4.1.17.Final"

lazy val `mesh-agent` = (project in file("mesh-agent"))
  .settings(
    name := "mesh-agent",
    version := "1.0-SNAPSHOT",
    scalaVersion := "2.12.6",
    mainClass in assembly := Some("mesh.Server"),
    assemblyMergeStrategy in assembly := {
      case PathList("META-INF", xs@_*) if xs.exists(_.endsWith(".so")) =>
        MergeStrategy.first
      case PathList("META-INF", xs@_*) =>
        MergeStrategy.discard
      case x if x.endsWith(".properties") =>
        MergeStrategy.first
      case x if x.endsWith(".conf") =>
        MergeStrategy.concat
      case x =>
        MergeStrategy.deduplicate
    },
    //    assemblyJarName in assembly := "agent.jar",
    libraryDependencies ++= Seq(
      "com.alibaba" % "fastjson" % "1.2.46",
      "com.typesafe.akka" %% "akka-stream" % "2.5.12",
      "com.coreos" % "jetcd-core" % "0.0.2",
      "io.netty" % "netty-transport-native-epoll" % nettyVersion classifier "linux-x86_64"
    )
  )


