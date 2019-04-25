name := "betway-scraper"

version := "0.1"

scalaVersion := "2.12.6"

lazy val akkaVersion = "2.5.19"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion,
  "org.scalatest" %% "scalatest" % "3.0.5" % "test",
  "io.spray" %% "spray-json" % "1.3.3",
  "com.surebetfinder" %% "surebetfinder-utils" % "0.1-SNAPSHOT"
)

artifactName := { (_: ScalaVersion, _: ModuleID, artifact: Artifact) =>
  artifact.name + "." + artifact.extension
}