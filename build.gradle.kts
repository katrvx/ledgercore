plugins {
    java
    groovy
    application
    jacoco
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

// the fraud evaluation lives in its own source set, so it never ships inside the app
val evaluation: SourceSet = sourceSets.create("evaluation") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[evaluation.implementationConfigurationName].extendsFrom(configurations.implementation.get())

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
    testImplementation(evaluation.output)
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

// show deprecated calls in the build output instead of a one-line note
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("fraudEvaluation") {
    description = "Runs the fraud rules on 10,000 synthetic labeled transactions and writes docs/fraud-evaluation.md"
    group = "verification"
    classpath = evaluation.runtimeClasspath
    mainClass = "com.ledgercore.evaluation.FraudEvaluation"
    // seed 1 was only used to debug the generator, the report uses seed 2
    args("2", layout.projectDirectory.file("docs/fraud-evaluation.md").asFile.path)
}

// generated jooq classes are not my code, so they don't count for coverage
val coveredClasses = sourceSets.main.get().output.classesDirs.asFileTree.matching {
    exclude("com/ledgercore/jooq/**")
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(coveredClasses)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(coveredClasses)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestReport, tasks.jacocoTestCoverageVerification)
}
