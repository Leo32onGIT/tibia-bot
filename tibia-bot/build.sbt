ThisBuild / version := "1.1.0-SNAPSHOT"

name := "tibia-bot"
version := "1.1"

scalaVersion := "2.13.9"

enablePlugins(DockerPlugin)
enablePlugins(JavaAppPackaging)
dockerExposedPorts += 443

val AkkaHttpVersion = "10.5.0"

libraryDependencies += "com.typesafe" % "config" % "1.4.2"
libraryDependencies += "com.typesafe.akka" %% "akka-stream" % "2.7.0"
libraryDependencies += "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion
libraryDependencies += "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion
libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5"
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.10"
libraryDependencies += "org.codehaus.janino" % "janino" % "3.1.6"
libraryDependencies += "com.github.napstr" % "logback-discord-appender" % "1.0.0"
libraryDependencies += "net.dv8tion" % "JDA" % "5.0.0-beta.4"
libraryDependencies += "club.minnced" % "discord-webhooks" % "0.8.2"
libraryDependencies += "org.apache.commons" % "commons-text" % "1.10.0"
libraryDependencies += "org.postgresql" % "postgresql" % "42.5.4"
libraryDependencies += "com.google.guava" % "guava" % "30.1.1-jre"

libraryDependencies += "org.scalactic" %% "scalactic" % "3.2.15"
libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.15" % Test
libraryDependencies += "org.scalamock" %% "scalamock" % "5.2.0" % Test

resolvers += "jitpack" at "https://jitpack.io"
