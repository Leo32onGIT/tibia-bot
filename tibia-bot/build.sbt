ThisBuild / version := "1.1.0-SNAPSHOT"

name := "tibia-bot"

version := "0.8"

scalaVersion := "2.13.9"

enablePlugins(DockerPlugin)
enablePlugins(JavaAppPackaging)
dockerExposedPorts += 443

val AkkaHttpVersion = "10.2.9"

libraryDependencies += "com.typesafe" % "config" % "1.4.2"
libraryDependencies += "com.typesafe.akka" %% "akka-stream" % "2.6.19"
libraryDependencies += "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion
libraryDependencies += "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion
libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4"
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.10"
libraryDependencies += "com.github.napstr" % "logback-discord-appender" % "1.0.0"
libraryDependencies += "net.dv8tion" % "JDA" % "5.0.0-beta.4"
libraryDependencies += "club.minnced" % "discord-webhooks" % "0.8.2"
libraryDependencies += "org.apache.commons" % "commons-text" % "1.9"
libraryDependencies += "org.postgresql" % "postgresql" % "42.2.14"

libraryDependencies += "org.scalactic" %% "scalactic" % "3.2.12"
libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.12" % Test
libraryDependencies += "org.scalamock" %% "scalamock" % "5.1.0" % Test

resolvers += "jitpack" at "https://jitpack.io"
