plugins {
    java
}

group = "io.ebean"
version = "15.1.0"

dependencies {
    compileOnly("io.ebean:ebean-joda-time:14.0.0")
    compileOnly("io.ebean:ebean-core:${version}")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.jar {
    archiveFileName = "ebean-querybean-${version}.jar"
    manifest {
        val at = attributes
        at["Implementation-Title"] = "ebean querybean"
        at["Implementation-Version"] = version
    }
}