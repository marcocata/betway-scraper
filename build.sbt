name := "betway-scraper"

version := "0.1"

scalaVersion := "2.12.8"

libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "3.0.5" % "test",
  "io.spray" %% "spray-json" % "1.3.3",
  "com.surebetfinder" %% "surebetfinder-utils" % "0.3"
)

mainClass in assembly := Some("com.betway.Main")
assemblyJarName in assembly := "betway-scraper.jar"

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case x => MergeStrategy.first
}