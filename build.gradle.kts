/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

import com.aliyun.polardb2.buildtools.JavaCommentPreprocessorTask
import com.github.spotbugs.SpotBugsTask
import com.github.vlsi.gradle.crlf.CrLfSpec
import com.github.vlsi.gradle.crlf.LineEndings
import com.github.vlsi.gradle.dsl.configureEach
import com.github.vlsi.gradle.git.FindGitAttributes
import com.github.vlsi.gradle.properties.dsl.props
import com.github.vlsi.gradle.publishing.dsl.simplifyXml
import com.github.vlsi.gradle.publishing.dsl.versionFromResolution
import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis
import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApisExtension

plugins {
    publishing
    // Verification
    checkstyle
    jacoco
    id("com.github.autostyle")
    id("com.github.spotbugs")
    id("org.owasp.dependencycheck")
    id("org.checkerframework") apply false
    id("com.github.johnrengelman.shadow") apply false
    id("de.thetaphi.forbiddenapis") apply false
    id("org.nosphere.gradle.github.actions")
    id("com.github.vlsi.jandex") apply false
    // IDE configuration
    id("org.jetbrains.gradle.plugin.idea-ext")
    id("com.github.vlsi.ide")
    // Release
    id("com.github.vlsi.crlf")
    id("com.github.vlsi.gradle-extensions")
    id("com.github.vlsi.license-gather") apply false
    id("com.github.vlsi.stage-vote-release")
}

fun reportsForHumans() = !(System.getenv()["CI"]?.toBoolean() ?: props.bool("CI"))

val lastEditYear = 2023 // TODO: by extra(lastEditYear("$rootDir/LICENSE"))

// Do not enable spotbugs by default. Execute it only when -Pspotbugs is present
val enableSpotBugs = props.bool("spotbugs", default = false)
val enableCheckerframework by props()
val skipCheckstyle by props()
val skipAutostyle by props()
val skipJavadoc by props()
val skipForbiddenApis by props()
val enableMavenLocal by props()
val enableGradleMetadata by props()
// For instance -PincludeTestTags=!com.aliyun.polardb2.test.SlowTests
//           or -PincludeTestTags=!com.aliyun.polardb2.test.Replication
val includeTestTags by props("")
// By default use Java implementation to sign artifacts
// When useGpgCmd=true, then gpg command line tool is used for signing
val useGpgCmd by props()
val jacocoEnabled by extra {
    props.bool("coverage") || gradle.startParameter.taskNames.any { it.contains("jacoco") }
}

ide {
    // TODO: set copyright to PostgreSQL Global Development Group
    // copyrightToAsf()
    ideaInstructionsUri =
        uri("https://github.com/pgjdbc/pgjdbc")
    doNotDetectFrameworks("android", "jruby")
}

// This task scans the project for gitignore / gitattributes, and that is reused for building
// source/binary artifacts with the appropriate eol/executable file flags
// It enables to automatically exclude patterns from .gitignore
val gitProps by tasks.registering(FindGitAttributes::class) {
    // Scanning for .gitignore and .gitattributes files in a task avoids doing that
    // when distribution build is not required (e.g. code is just compiled)
    root.set(rootDir)
}

val String.v: String get() = rootProject.extra["$this.version"] as String

val buildVersion = "pgjdbc".v + releaseParams.snapshotSuffix

println("Building pgjdbc $buildVersion")

val isReleaseVersion = rootProject.releaseParams.release.get()

// Configures URLs to SVN and Nexus

val licenseHeaderFile = file("config/license.header.java")

val jacocoReport by tasks.registering(JacocoReport::class) {
    group = "Coverage reports"
    description = "Generates an aggregate report from all subprojects"
}

// Alias for the internal release platform which invokes the Android-style
// "./gradlew assembleRelease" and cannot be reconfigured on the platform side.
// It builds all driver artifacts (jar, all/osgi jars, sources, javadoc).
// Note: settings.gradle.kts turns this invocation into a release build
// (equivalent to -Prelease), so the produced jars have no -SNAPSHOT suffix.
tasks.register("assembleRelease") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Alias of :polardb:assemble for the internal release platform"
    dependsOn(":polardb:assemble")
}

// Aliases for the internal release platform which invokes the
// io.github.gradle-nexus.publish-plugin style commands
//   ./gradlew publishToSonatype closeSonatypeStagingRepository
//   ./gradlew findSonatypeStagingRepository releaseSonatypeStagingRepository
// and cannot be reconfigured on the platform side. That plugin itself cannot be
// applied here (extension name clash with the bundled de.marcphilipp.nexus-publish,
// see the "Publishing to an internal Nexus platform" note below), so equivalent
// tasks are registered manually on top of the Central Portal OSSRH-compatible API
// (https://ossrh-staging-api.central.sonatype.com):
// - publishToSonatype: publishes all publications to the Central staging endpoint
//   (same as publishAllPublicationsToCentralRepository)
// - closeSonatypeStagingRepository: no-op; with the Portal compatibility API the
//   validation ("close") happens after the deployment upload performed by release
// - findSonatypeStagingRepository: lists the open staging repositories of the
//   com.aliyun.polardb2 namespace
// - releaseSonatypeStagingRepository: moves the staged artifacts into a Central
//   Portal deployment with publishing_type=automatic, i.e. they are validated and
//   published to Maven Central without further manual confirmation
// Note: settings.gradle.kts turns these invocations into a release build (-Prelease).
fun centralStagingApi(method: String, path: String): String {
    val username = sonatypeCredential("Username")
        ?: throw GradleException(
            "Central credentials missing: set the sonatypeUsername project property " +
                "(e.g. via the ORG_GRADLE_PROJECT_sonatypeUsername environment variable)"
        )
    val password = sonatypeCredential("Password")
        ?: throw GradleException(
            "Central credentials missing: set the sonatypePassword project property " +
                "(e.g. via the ORG_GRADLE_PROJECT_sonatypePassword environment variable)"
        )
    val token = java.util.Base64.getEncoder()
        .encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
    val connection = java.net.URL("https://ossrh-staging-api.central.sonatype.com$path")
        .openConnection() as java.net.HttpURLConnection
    connection.requestMethod = method
    connection.setRequestProperty("Authorization", "Bearer $token")
    val responseCode = connection.responseCode
    val body = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
        ?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
    if (responseCode !in 200..299) {
        throw GradleException("Central staging API $method $path failed: HTTP $responseCode $body")
    }
    return body
}

tasks.register("publishToSonatype") {
    group = "publishing"
    description = "Alias: publishes all publications to the Central repository"
    dependsOn(
        allprojects.map { p ->
            p.tasks.matching { it.name == "publishAllPublicationsToCentralRepository" }
        }
    )
}
tasks.register("closeSonatypeStagingRepository") {
    group = "publishing"
    description = "Alias: no-op, Central Portal validates the deployment after release uploads it"
    mustRunAfter("publishToSonatype")
    onlyIf { isReleaseVersion }
    doLast {
        println(
            "Nothing to close: with the Central Portal OSSRH-compatible flow the staged " +
                "artifacts are validated after releaseSonatypeStagingRepository uploads the deployment."
        )
    }
}
tasks.register("findSonatypeStagingRepository") {
    group = "publishing"
    description = "Alias: lists the Central staging repositories of com.aliyun.polardb2"
    mustRunAfter("publishToSonatype", "closeSonatypeStagingRepository")
    onlyIf { isReleaseVersion }
    doLast {
        val body = centralStagingApi(
            "GET",
            "/manual/search/repositories?profile_id=com.aliyun.polardb2"
        )
        println("Central staging repositories: $body")
    }
}
tasks.register("releaseSonatypeStagingRepository") {
    group = "publishing"
    description = "Alias: publishes the staged Central deployment to Maven Central"
    mustRunAfter("publishToSonatype", "closeSonatypeStagingRepository", "findSonatypeStagingRepository")
    onlyIf { isReleaseVersion }
    doLast {
        // A repository left in "closed" state (e.g. by an earlier interrupted run or
        // by a previous user_managed upload) blocks any further release with
        // HTTP 400 "must be dropped before a new release can occur", so stale
        // closed repositories are dropped first. Their content has already been
        // transferred to a Central Portal deployment, the staging repository
        // itself holds no unique data anymore.
        val search = centralStagingApi(
            "GET",
            "/manual/search/repositories?profile_id=com.aliyun.polardb2"
        )
        val parsed = groovy.json.JsonSlurper().parseText(search) as? Map<*, *>
        val repositories = (parsed?.get("repositories") as? List<*>).orEmpty()
        repositories.filterIsInstance<Map<*, *>>()
            .filter { it["state"] == "closed" }
            .forEach { repo ->
                val key = repo["key"].toString()
                println("Dropping stale closed Central staging repository: $key")
                centralStagingApi(
                    "DELETE",
                    "/manual/drop/repository/" + java.net.URLEncoder.encode(key, "UTF-8")
                )
            }
        centralStagingApi(
            "POST",
            "/manual/upload/defaultRepository/com.aliyun.polardb2?publishing_type=automatic"
        )
        println(
            "Central staging deployment uploaded with publishing_type=automatic. " +
                "Progress can be tracked at https://central.sonatype.com/publishing"
        )
    }
}

releaseParams {
    tlp.set("pgjdbc")
    organizationName.set("pgjdbc")
    componentName.set("pgjdbc")
    prefixForProperties.set("gh")
    svnDistEnabled.set(false)
    sitePreviewEnabled.set(false)
    releaseTag.set("REL$buildVersion")
    nexus {
        mavenCentral()
    }
    voteText.set {
        """
        ${it.componentName} v${it.version}-rc${it.rc} is ready for preview.

        Git SHA: ${it.gitSha}
        Staging repository: ${it.nexusRepositoryUri}
        """.trimIndent()
    }
}

// Publishing to an internal Nexus platform.
// Note: io.github.gradle-nexus.publish-plugin cannot be applied directly because
// com.github.vlsi.stage-vote-release bundles its predecessor (de.marcphilipp.nexus-publish,
// donated to the gradle-nexus org and renamed) which registers the same 'nexusPublishing'
// extension on the root project. The DSL and tasks are identical, so the internal
// repository is registered on that bundled extension instead (see allprojects below).
// Tasks (per publishing project, e.g. :polardb):
//   ./gradlew publishToInternal            - publish all publications to the internal Nexus
//   ./gradlew publishToInternal -Prelease  - publish a release (staged when staging is enabled)
// Configuration (Gradle properties or environment variables):
//   internalNexusUrl / INTERNAL_NEXUS_URL                   e.g. https://nexus.example.com/service/local/
//   internalNexusSnapshotUrl / INTERNAL_NEXUS_SNAPSHOT_URL  e.g. https://nexus.example.com/content/repositories/snapshots/
//   internalNexusUsername / INTERNAL_NEXUS_USERNAME
//   internalNexusPassword / INTERNAL_NEXUS_PASSWORD
// If the internal Nexus does not support the staging workflow (e.g. plain Nexus 3),
// pass -PinternalNexusUseStaging=false to publish directly to internalNexusUrl.
fun stringProp(propName: String, envName: String): String? =
    (project.findProperty(propName) as? String)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envName)?.takeIf { it.isNotBlank() }

// Central credential lookup following the gradle-nexus/publish-plugin convention
// (https://github.com/gradle-nexus/publish-plugin): for a repository named "sonatype"
// the credentials default to the sonatypeUsername/sonatypePassword project properties,
// which can be provided in ~/.gradle/gradle.properties or injected as the
// ORG_GRADLE_PROJECT_sonatypeUsername / ORG_GRADLE_PROJECT_sonatypePassword
// environment variables. The legacy centralPortal* properties and CENTRAL_PORTAL_*
// environment variables are kept as fallback for existing setups.
fun sonatypeCredential(kind: String): String? =
    (project.findProperty("sonatype$kind") as? String)?.takeIf { it.isNotBlank() }
        ?: stringProp("centralPortal$kind", "CENTRAL_PORTAL_${kind.toUpperCase()}")

allprojects {
    group = "com.aliyun.polardb2"
    version = buildVersion

    apply(plugin = "com.github.vlsi.gradle-extensions")

    plugins.withId("de.marcphilipp.nexus-publish") {
        configure<de.marcphilipp.gradle.nexus.NexusPublishExtension> {
            clientTimeout.set(java.time.Duration.ofMinutes(15))
            // Internal platform repository, see "Publishing to an internal Nexus platform" above.
            // The repository (and its publishToInternal task) only exists when internalNexusUrl
            // is configured, so regular builds are not affected.
            stringProp("internalNexusUrl", "INTERNAL_NEXUS_URL")?.let { internalUrl ->
                repositories.create("internal") {
                    nexusUrl.set(uri(internalUrl))
                    snapshotRepositoryUrl.set(
                        uri(stringProp("internalNexusSnapshotUrl", "INTERNAL_NEXUS_SNAPSHOT_URL") ?: internalUrl)
                    )
                    username.set(stringProp("internalNexusUsername", "INTERNAL_NEXUS_USERNAME"))
                    password.set(stringProp("internalNexusPassword", "INTERNAL_NEXUS_PASSWORD"))
                }
                if (!props.bool("internalNexusUseStaging", default = true)) {
                    useStaging.set(false)
                }
            }
        }
    }

    plugins.withId("io.codearte.nexus-staging") {
        configure<io.codearte.gradle.nexus.NexusStagingExtension> {
            numberOfRetries = 20 * 60 / 2
            delayBetweenRetriesInMillis = 2000
        }
    }

    repositories {
        if (enableMavenLocal) {
            mavenLocal()
        }
        "http://mvnrepo.alibaba-inc.com/mvn/repository"
        mavenCentral()
    }

    val javaMainUsed = file("src/main/java").isDirectory
    val javaTestUsed = file("src/test/java").isDirectory
    val javaUsed = javaMainUsed || javaTestUsed
    if (javaUsed) {
        apply(plugin = "java-library")
        if (jacocoEnabled) {
            apply(plugin = "jacoco")
        }
    }

    plugins.withId("java-library") {
        dependencies {
            "implementation"(platform(project(":bom")))
        }
    }

    val kotlinMainUsed = file("src/main/kotlin").isDirectory
    val kotlinTestUsed = file("src/test/kotlin").isDirectory
    val kotlinUsed = kotlinMainUsed || kotlinTestUsed
    if (kotlinUsed) {
        apply(plugin = "java-library")
        apply(plugin = "org.jetbrains.kotlin.jvm")
        dependencies {
            add(if (kotlinMainUsed) "implementation" else "testImplementation", kotlin("stdlib"))
        }
    }

    val hasTests = javaTestUsed || kotlinTestUsed
    if (hasTests) {
        // Add default tests dependencies
        dependencies {
            val testImplementation by configurations
            val testRuntimeOnly by configurations
            testImplementation("org.junit.jupiter:junit-jupiter-api")
            testImplementation("uk.org.webcompere:system-stubs-jupiter")
            testImplementation("org.junit.jupiter:junit-jupiter-params")
            testImplementation("org.hamcrest:hamcrest")
            testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
            if (project.props.bool("junit4", default = true)) {
                // Allow projects to opt-out of junit dependency, so they can be JUnit5-only
                testImplementation("junit:junit")
                testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
            }
        }
    }

    if (!skipAutostyle) {
        apply(plugin = "com.github.autostyle")
        autostyle {
            kotlinGradle {
                ktlint()
                trimTrailingWhitespace()
                endWithNewline()
            }
            format("markdown") {
                target("**/*.md")
                endWithNewline()
            }
        }
    }
    val skipCheckstyle = skipCheckstyle || props.bool("skipCheckstyle")
    if (!skipCheckstyle) {
        apply<CheckstylePlugin>()
        dependencies {
            checkstyle("com.puppycrawl.tools:checkstyle:${"checkstyle".v}")
        }
        checkstyle {
            // Current one is ~8.8
            // https://github.com/julianhyde/toolbox/issues/3
            isShowViolations = true
            // TOOD: move to /config
            configDirectory.set(File(rootDir, "pgjdbc/src/main/checkstyle"))
            configFile = configDirectory.get().file("checks.xml").asFile
        }

        val checkstyleTasks = tasks.withType<Checkstyle>()
        checkstyleTasks.configureEach {
            // Checkstyle 8.26 does not need classpath, see https://github.com/gradle/gradle/issues/14227
            classpath = files()
        }

        tasks.register("checkstyleAll") {
            dependsOn(checkstyleTasks)
        }
    }
    if (!skipAutostyle || !skipCheckstyle) {
        tasks.register("style") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Formats code (license header, import order, whitespace at end of line, ...) and executes Checkstyle verifications"
            if (!skipAutostyle) {
                dependsOn("autostyleApply")
            }
            if (!skipCheckstyle) {
                dependsOn("checkstyleAll")
            }
        }
    }

    tasks.configureEach<AbstractArchiveTask> {
        // Ensure builds are reproducible
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        dirMode = "775".toInt(8)
        fileMode = "664".toInt(8)
    }

    plugins.withType<SigningPlugin> {
        afterEvaluate {
            configure<SigningExtension> {
                val release = rootProject.releaseParams.release.get()
                // Note it would still try to sign the artifacts,
                // however it would fail only when signing a RELEASE version fails
                isRequired = release
                if (useGpgCmd) {
                    useGpgCmd()
                }
            }
        }
    }

    plugins.withType<JacocoPlugin> {
        the<JacocoPluginExtension>().toolVersion = "jacoco".v

        val testTasks = tasks.withType<Test>()
        val javaExecTasks = tasks.withType<JavaExec>()
        // This configuration must be postponed since JacocoTaskExtension might be added inside
        // configure block of a task (== before this code is run). See :src:dist-check:createBatchTask
        afterEvaluate {
            for (t in arrayOf(testTasks, javaExecTasks)) {
                t.configureEach {
                    extensions.findByType<JacocoTaskExtension>()?.apply {
                        // Do not collect coverage when not asked (e.g. via jacocoReport or -Pcoverage)
                        isEnabled = jacocoEnabled
                        // We don't want to collect coverage for third-party classes
                        includes?.add("com.aliyun.polardb2.*")
                    }
                }
            }
        }

        jacocoReport {
            // Note: this creates a lazy collection
            // Some of the projects might fail to create a file (e.g. no tests or no coverage),
            // So we check for file existence. Otherwise JacocoMerge would fail
            val execFiles =
                    files(testTasks, javaExecTasks).filter { it.exists() && it.name.endsWith(".exec") }
            executionData(execFiles)
        }

        tasks.configureEach<JacocoReport> {
            reports {
                html.required.set(reportsForHumans())
                xml.required.set(!reportsForHumans())
            }
        }
    }

    tasks {
        // <editor-fold defaultstate="collapsed" desc="JavaCommentPreprocessor: configure version variables">
        configureEach<JavaCommentPreprocessorTask> {
            variables.apply {
                val jdbcSpec = props.string("jdbc.specification.version")
                put("mvn.project.property.postgresql.jdbc.spec", "JDBC$jdbcSpec")
                put("jdbc.specification.version", jdbcSpec)
            }

            val re = Regex("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?.*")

            val version = project.version.toString()
            val matchResult = re.find(version) ?: throw GradleException("Unable to parse major.minor.patch version parts from project.version '$version'")
            val (major, minor, patch) = matchResult.destructured

            variables.apply {
                put("version", version)
                put("version.major", major)
                put("version.minor", minor)
                put("version.patch", patch.ifBlank { "0" })
            }
        }
        // </editor-fold>

        // <editor-fold defaultstate="collapsed" desc="Javadoc configuration">
        configureEach<Javadoc> {
            (options as StandardJavadocDocletOptions).apply {
                // Please refrain from using non-ASCII chars below since the options are passed as
                // javadoc.options file which is parsed with "default encoding"
                noTimestamp.value = true
                showFromProtected()
                if (props.bool("failOnJavadocWarning", default = true)) {
                    // See JDK-8200363 (https://bugs.openjdk.java.net/browse/JDK-8200363)
                    // for information about the -Xwerror option.
                    addBooleanOption("Xwerror", true)
                }
                // There are too many missing javadocs, so failing the build on missing comments seems to be not an option
                addBooleanOption("Xdoclint:all,-missing", true)
                // javadoc: error - The code being documented uses modules but the packages
                // defined in https://docs.oracle.com/javase/9/docs/api/ are in the unnamed module
                source = "1.8"
                docEncoding = "UTF-8"
                charSet = "UTF-8"
                encoding = "UTF-8"
                docTitle = "PostgreSQL JDBC ${project.name} API version ${project.version}"
                windowTitle = "PostgreSQL JDBC ${project.name} API version ${project.version}"
                header = "<b>PostgreSQL JDBC</b>"
                bottom =
                    "Copyright &copy; 1997-$lastEditYear PostgreSQL Global Development Group. All Rights Reserved."
                if (JavaVersion.current() >= JavaVersion.VERSION_17) {
                    addBooleanOption("html5", true)
                } else if (JavaVersion.current() >= JavaVersion.VERSION_1_9) {
                    addBooleanOption("html5", true)
                    links("https://docs.oracle.com/javase/9/docs/api/")
                } else {
                    links("https://docs.oracle.com/javase/8/docs/api/")
                }
            }
        }
        // </editor-fold>
    }

    plugins.withType<JavaPlugin> {
        configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
            withSourcesJar()
            if (!skipJavadoc) {
                withJavadocJar()
            }
        }

        val sourceSets: SourceSetContainer by project

        apply(plugin = "com.github.vlsi.jandex")
        apply(plugin = "maven-publish")

        project.configure<com.github.vlsi.jandex.JandexExtension> {
            skipIndexFileGeneration()
        }

        if (!enableGradleMetadata) {
            tasks.withType<GenerateModuleMetadata> {
                enabled = false
            }
        }

        if (!skipForbiddenApis && !props.bool("skipCheckstyle")) {
            apply(plugin = "de.thetaphi.forbiddenapis")
            configure<CheckForbiddenApisExtension> {
                failOnUnsupportedJava = false
                signaturesFiles = files("$rootDir/config/forbidden-apis/forbidden-apis.txt")
                bundledSignatures.addAll(
                    listOf(
                        // "jdk-deprecated",
                        "jdk-internal",
                        "jdk-non-portable"
                        // "jdk-system-out"
                        // "jdk-unsafe"
                    )
                )
            }
            tasks.configureEach<CheckForbiddenApis> {
                exclude("**/com/aliyun/polardb2/util/internal/Unsafe.class")
            }
        }

        if (!skipAutostyle) {
            autostyle {
                java {
                    // targetExclude("**/test/java/*.java")
                    // TODO: implement license check (with copyright year)
                    // licenseHeaderFile(licenseHeaderFile)
                    importOrder(
                        "static ",
                        "com.aliyun.polardb2.",
                        "",
                        "java.",
                        "javax."
                    )
                    removeUnusedImports()
                    trimTrailingWhitespace()
                    indentWithSpaces(4)
                    endWithNewline()
                }
            }
        }

        if (enableCheckerframework) {
            apply(plugin = "org.checkerframework")
            dependencies {
                "checkerFramework"("org.checkerframework:checker:${"checkerframework".v}")
                // CheckerFramework annotations might be used in the code as follows:
                // dependencies {
                //     "compileOnly"("org.checkerframework:checker-qual")
                //     "testCompileOnly"("org.checkerframework:checker-qual")
                // }
                if (JavaVersion.current() == JavaVersion.VERSION_1_8) {
                    // only needed for JDK 8
                    "checkerFrameworkAnnotatedJDK"("org.checkerframework:jdk8:${"checkerframework".v}")
                }
            }
            configure<org.checkerframework.gradle.plugin.CheckerFrameworkExtension> {
                skipVersionCheck = true
                excludeTests = true
                // See https://checkerframework.org/manual/#introduction
                checkers.add("org.checkerframework.checker.nullness.NullnessChecker")
                checkers.add("org.checkerframework.checker.optional.OptionalChecker")
                // checkers.add("org.checkerframework.checker.index.IndexChecker")
                checkers.add("org.checkerframework.checker.regex.RegexChecker")
                extraJavacArgs.add("-Astubs=" +
                        fileTree("$rootDir/config/checkerframework") {
                            include("*.astub")
                        }.asPath
                )
                // Translation classes are autogenerated, and they
                extraJavacArgs.add("-AskipDefs=^org\\.postgresql\\.translation\\.")
                // The below produces too many warnings :(
                // extraJavacArgs.add("-Alint=redundantNullComparison")
            }
        }

        if (jacocoEnabled) {
            // Add each project to combined report
            val mainCode = sourceSets["main"]
            jacocoReport.configure {
                additionalSourceDirs.from(mainCode.allJava.srcDirs)
                sourceDirectories.from(mainCode.allSource.srcDirs)
                classDirectories.from(mainCode.output)
            }
        }

        if (enableSpotBugs) {
            apply(plugin = "com.github.spotbugs")
            spotbugs {
                toolVersion = "spotbugs".v
                reportLevel = "high"
                //  excludeFilter = file("$rootDir/src/main/config/spotbugs/spotbugs-filter.xml")
                // By default spotbugs verifies TEST classes as well, and we do not want that
                this.sourceSets = listOf(sourceSets["main"])
            }
            dependencies {
                // Parenthesis are needed here: https://github.com/gradle/gradle/issues/9248
                (constraints) {
                    "spotbugs"("org.ow2.asm:asm:${"asm".v}")
                    "spotbugs"("org.ow2.asm:asm-all:${"asm".v}")
                    "spotbugs"("org.ow2.asm:asm-analysis:${"asm".v}")
                    "spotbugs"("org.ow2.asm:asm-commons:${"asm".v}")
                    "spotbugs"("org.ow2.asm:asm-tree:${"asm".v}")
                    "spotbugs"("org.ow2.asm:asm-util:${"asm".v}")
                }
            }
        }

        (sourceSets) {
            "main" {
                resources {
                    // TODO: remove when LICENSE is removed (it is used by Maven build for now)
                    exclude("src/main/resources/META-INF/LICENSE")
                }
            }
        }

        tasks {
            configureEach<Jar> {
                manifest {
                    attributes["Bundle-License"] = "Apache-2.0"
                    attributes["Implementation-Title"] = "PostgreSQL JDBC Driver"
                    attributes["Implementation-Version"] = project.version
                    val jdbcSpec = props.string("jdbc.specification.version")
                    if (jdbcSpec.isNotBlank()) {
                        attributes["Specification-Vendor"] = "Oracle Corporation"
                        attributes["Specification-Version"] = jdbcSpec
                        attributes["Specification-Title"] = "JDBC"
                    }
                    attributes["Implementation-Vendor"] = "PostgreSQL Global Development Group"
                    attributes["Implementation-Vendor-Id"] = "com.aliyun.polardb2"
                }
            }

            configureEach<JavaCompile> {
                options.encoding = "UTF-8"
            }
            configureEach<Test> {
                useJUnitPlatform {
                    if (includeTestTags.isNotBlank()) {
                        includeTags.add(includeTestTags)
                    }
                }
                inputs.file("../build.properties")
                if (file("../build.local.properties").exists()) {
                    inputs.file("../build.local.properties")
                }
                inputs.file("../ssltest.properties")
                if (file("../ssltest.local.properties").exists()) {
                    inputs.file("../ssltest.local.properties")
                }
                testLogging {
                    showStandardStreams = true
                }
                exclude("**/*Suite*")
                jvmArgs("-Xmx1536m")
                jvmArgs("-Djdk.net.URLClassPath.disableClassPathURLCheck=true")
                // Pass the property to tests
                fun passProperty(name: String, default: String? = null) {
                    val value = System.getProperty(name) ?: default
                    value?.let { systemProperty(name, it) }
                }
                passProperty("preferQueryMode")
                passProperty("java.awt.headless")
                passProperty("junit.jupiter.execution.parallel.enabled", "true")
                // TODO: remove when upgrade to JUnit 5.9+
                // See https://github.com/junit-team/junit5/commit/347e3119d36a5c226cddd7981452f11335fad422
                passProperty("junit.jupiter.execution.parallel.config.strategy", "DYNAMIC")
                passProperty("junit.jupiter.execution.timeout.default", "5 m")
                passProperty("user.language", "TR")
                passProperty("user.country", "tr")
                val props = System.getProperties()
                for (e in props.propertyNames() as `java.util`.Enumeration<String>) {
                    if (e.startsWith("pgjdbc.") || e.startsWith("java")) {
                        passProperty(e)
                    }
                }
                for (p in listOf("server", "port", "database", "username", "password",
                        "privilegedUser", "privilegedPassword",
                        "simpleProtocolOnly", "enable_ssl_tests")) {
                    passProperty(p)
                }
            }
            configureEach<SpotBugsTask> {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                if (enableSpotBugs) {
                    description = "$description (skipped by default, to enable it add -Dspotbugs)"
                }
                reports {
                    html.required.set(reportsForHumans())
                    xml.required.set(!reportsForHumans())
                }
                enabled = enableSpotBugs
            }

            afterEvaluate {
                // Add default license/notice when missing
                configureEach<Jar> {
                    CrLfSpec(LineEndings.LF).run {
                        into("META-INF") {
                            filteringCharset = "UTF-8"
                            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                            // Note: we need "generic Apache-2.0" text without third-party items
                            // So we use the text from $rootDir/config/ since source distribution
                            // contains altered text at $rootDir/LICENSE
                            textFrom("$rootDir/src/main/config/licenses/LICENSE")
                            textFrom("$rootDir/NOTICE")
                        }
                    }
                }
            }
        }

        configure<PublishingExtension> {
            if (!project.props.bool("nexus.publish", default = true)) {
                // Some of the artifacts do not need to be published
                return@configure
            }

            // Sonatype Central Portal (https://central.sonatype.com)
            // Legacy OSSRH (oss.sonatype.org) was sunset in June 2025; artifacts are now
            // deployed via the Central Portal OSSRH-compatible endpoints.
            // Credentials follow the gradle-nexus/publish-plugin convention:
            //   sonatypeUsername / sonatypePassword project properties
            //   (~/.gradle/gradle.properties, -P options, or the
            //   ORG_GRADLE_PROJECT_sonatypeUsername / ORG_GRADLE_PROJECT_sonatypePassword
            //   environment variables); centralPortal* / CENTRAL_PORTAL_* kept as fallback.
            // Publish with: ./gradlew publishAllPublicationsToCentralRepository -Prelease
            repositories {
                maven {
                    name = "central"
                    val isSnapshot = project.version.toString().endsWith("-SNAPSHOT")
                    url = uri(
                        if (isSnapshot) {
                            "https://central.sonatype.com/repository/maven-snapshots/"
                        } else {
                            "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
                        }
                    )
                    credentials {
                        username = sonatypeCredential("Username")
                        password = sonatypeCredential("Password")
                    }
                }
            }

            publications {
                // <editor-fold defaultstate="collapsed" desc="Override published artifacts (e.g. shaded instead of regular)">
                val extraMavenPublications by configurations.creating {
                    isVisible = false
                    isCanBeResolved = false
                    isCanBeConsumed = false
                }
                afterEvaluate {
                    named<MavenPublication>(project.name) {
                        extraMavenPublications.outgoing.artifacts.apply {
                            val keys = mapTo(HashSet()) {
                                it.classifier.orEmpty() to it.extension
                            }
                            artifacts.removeIf {
                                keys.contains(it.classifier.orEmpty() to it.extension)
                            }
                            forEach { artifact(it) }
                        }
                    }
                }
                // </editor-fold>
                // <editor-fold defaultstate="collapsed" desc="Configuration of the published pom.xml">
                create<MavenPublication>(project.name) {
                    artifactId = project.name
                    version = rootProject.version.toString()
                    from(components["java"])

                    // Gradle feature variants can't be mapped to Maven's pom
                    suppressAllPomMetadataWarnings()

                    // Use the resolved versions in pom.xml
                    // Gradle might have different resolution rules, so we set the versions
                    // that were used in Gradle build/test.
                    versionFromResolution()
                    pom {
                        simplifyXml()
                        name.set(
                            (project.findProperty("artifact.name") as? String) ?: "PolarDB JDBC ${project.name.capitalize()}"
                        )
                        description.set(project.description ?: "PolarDB JDBC Driver (PostgreSQL/Oracle compatible) ${project.name.capitalize()}")
                        inceptionYear.set("1997")
                        url.set("https://www.alibabacloud.com/product/polardb")
                        licenses {
                            license {
                                name.set("Apache-2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0")
                                comments.set("Apache License, Version 2.0, copyright Alibaba Group Holding Limited")
                                distribution.set("repo")
                            }
                        }
                        organization {
                            name.set("Alibaba Cloud")
                            url.set("https://www.alibabacloud.com")
                        }
                        developers {
                            developer {
                                id.set("polardb")
                                name.set("PolarDB Development Team")
                                organization.set("Alibaba Cloud")
                                organizationUrl.set("https://www.alibabacloud.com")
                            }
                        }
                        issueManagement {
                            system.set("GitHub issues")
                            url.set("https://github.com/ApsaraDB/polardb_for_jdbc/issues")
                        }
                        scm {
                            connection.set("scm:git:https://github.com/ApsaraDB/polardb_for_jdbc.git")
                            developerConnection.set("scm:git:https://github.com/ApsaraDB/polardb_for_jdbc.git")
                            url.set("https://github.com/ApsaraDB/polardb_for_jdbc")
                            tag.set("HEAD")
                        }
                    }
                }
                // </editor-fold>
            }
        }

        // Maven Central requires PGP signatures (.asc) for every published artifact.
        // Signing is enforced only for release versions (see SigningPlugin config above);
        // for SNAPSHOT builds the sign tasks are skipped when no key is configured.
        // Provide the key via standard Gradle properties in ~/.gradle/gradle.properties:
        //   signing.keyId=XXXXXXXX
        //   signing.password=***
        //   signing.secretKeyRingFile=/Users/xxx/.gnupg/secring.gpg
        // or use the gpg command line tool with -PuseGpgCmd
        // Note: in release mode (-Prelease) the stage-vote-release plugin already signs
        // the publications itself, so the explicit sign() below would register duplicate
        // signXxxPublication tasks and fail the build; it is applied for SNAPSHOT only.
        if (project.props.bool("nexus.publish", default = true) && !isReleaseVersion) {
            apply(plugin = "signing")
            configure<SigningExtension> {
                sign(the<PublishingExtension>().publications)
            }
        }
    }
}

subprojects {
    if (project.path.startsWith(":polardb")) {
        plugins.withId("java") {
            configure<JavaPluginExtension> {
                val sourceSets: SourceSetContainer by project
                registerFeature("sspi") {
                    usingSourceSet(sourceSets["main"])
                }
                registerFeature("osgi") {
                    usingSourceSet(sourceSets["main"])
                }
            }
            dependencies {
                "sspiImplementation"("com.github.waffle:waffle-jna")
                // The dependencies are provided by OSGi container,
                // so they should not be exposed as transitive dependencies
                "osgiCompileOnly"("org.osgi:org.osgi.core")
                "osgiCompileOnly"("org.osgi:org.osgi.service.jdbc")
                "testImplementation"("org.osgi:org.osgi.service.jdbc") {
                    because("DataSourceFactory is needed for PGDataSourceFactoryTest")
                }
            }
        }
    }
}
