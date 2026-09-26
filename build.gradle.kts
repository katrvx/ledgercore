plugins {
    java
    groovy
    application
}

group = "com.ledgercore"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.sparkjava:spark-core:2.9.4")
    implementation("ch.qos.logback:logback-classic:1.5.38")

    testImplementation(platform("org.apache.groovy:groovy-bom:4.0.33"))
    testImplementation("org.apache.groovy:groovy-json")
    testImplementation("org.spockframework:spock-core:2.4-groovy-4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "com.ledgercore.App"
}

tasks.test {
    useJUnitPlatform()
}
