pluginManagement {
	repositories {
		gradlePluginPortal()
		mavenCentral()
		mavenLocal()

		maven("https://maven.architectury.dev")
	}
}

val projectName: String by settings
rootProject.name = projectName
include("bootstrap")
