name := "betway-scraper"

version := "0.2.1"

scalaVersion := "2.12.8"

libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "3.0.5" % "test",
  "com.google.code.gson" % "gson" % "2.8.5",
  "com.surebetfinder" %% "surebetfinder-utils" % "0.5.4"
)

mainClass in assembly := Some("com.betway.Main")
assemblyJarName in assembly := "betway-scraper.jar"

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case x => MergeStrategy.first
}