plugins {
    java
    groovy
    application
    id("org.jooq.jooq-codegen-gradle") version "3.21.9"
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

val jooqVersion = "3.21.9"
val flywayVersion = "13.8.0"

dependencies {
    implementation("com.sparkjava:spark-core:2.9.4")
    implementation("ch.qos.logback:logback-classic:1.5.38")
    implementation("tools.jackson.core:jackson-databind:3.2.3")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")
    implementation("org.jooq:jooq:$jooqVersion")
    implementation("io.lettuce:lettuce-core:7.8.0.RELEASE")

    jooqCodegen("org.jooq:jooq-meta-extensions:$jooqVersion")

    testImplementation(platform("org.apache.groovy:groovy-bom:4.0.33"))
    testImplementation("org.apache.groovy:groovy-json")
    testImplementation("org.apache.groovy:groovy-sql")
    testImplementation("org.spockframework:spock-core:2.4-groovy-4.0")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val migrations = layout.projectDirectory.dir("src/main/resources/db/migration")
val generatedJooq = layout.buildDirectory.dir("generated-sources/jooq")

// jooq reads the flyway scripts directly, so the build does not need a database
jooq {
    configuration {
        generator {
            database {
                name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                properties {
                    property {
                        key = "scripts"
                        value = migrations.asFile.path + "/*.sql"
                    }
                    property {
                        key = "sort"
                        value = "flyway"
                    }
                    property {
                        key = "defaultNameCase"
                        value = "lower"
                    }
                }
            }
            target {
                packageName = "com.ledgercore.jooq"
                directory = generatedJooq.get().asFile.path
            }
        }
    }
}

tasks.named("jooqCodegen") {
    inputs.dir(migrations)
    outputs.dir(generatedJooq)
}

sourceSets {
    main {
        java.srcDir(generatedJooq)
    }
}

tasks.compileJava {
    dependsOn(tasks.named("jooqCodegen"))
}

application {
    mainClass = "com.ledgercore.App"
}

tasks.test {
    useJUnitPlatform()
}
