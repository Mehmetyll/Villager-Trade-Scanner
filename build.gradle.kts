import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    // Applies the correct Loom variant for the target Minecraft version. Loom's
    // dependency API changed at 26.1, so this is what lets one build script span
    // both the 26 line and 1.21.x.
    id("dev.kikugie.loom-back-compat")
}

// Read once at the project level: inside `tasks { }` and extension blocks,
// `property(...)` resolves against the task, not the project.
val modId = property("mod.id").toString()
val modName = property("mod.name").toString()
val modVersion = property("mod.version").toString()
val archivesName = property("mod.archives_name").toString()
val mcCompat = property("mod.mc_compat").toString()
val loaderVersion = property("deps.fabric_loader").toString()
val fabricApiVersion = property("deps.fabric_api").toString()

// VillagerTrade only exists from 26 on, so the accessor targeting it is compiled
// away below that -- and a mixin config naming a class that is not there is a
// hard failure at load, not a warning.
val villagerTradeMixinEntry: String =
    if (sc.current.parsed >= "26.1") ",\n    \"VillagerTradeAccessor\"" else ""

// From 1.21.9 the entity render state has an outlineColor field and the glow is
// just an assignment. Below that it needs a mixin on Entity, which only exists
// there -- and naming a missing class in a mixin config is a hard failure.
val glowMixinEntry: String =
    if (sc.current.parsed < "1.21.9") ",\n    \"EntityGlowMixin\"" else ""

// Loom needs `group` left unset here.
version = "$modVersion+mc${sc.current.version}"
base.archivesName = archivesName

// Every 26.x release requires Java 25, every 1.21.x requires Java 21 -- verified
// against Mojang's version manifest, so this is one boundary rather than a
// per-version knob.
val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    else -> JavaVersion.VERSION_21
}

// The Minecraft releases this one jar covers.
// Patch releases sharing an API generation ride on the same jar.
val compatibleVersions: List<String> = sc.properties.rawOrNull("mod", "mc_releases")
    ?.asList().orEmpty().map { it.toString() }

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")

    // This project is written against Mojang official mappings, not Yarn.
    loomx.applyMojangMappings()

    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
}

loom {
    splitEnvironmentSourceSets()

    mods {
        create(modId) {
            sourceSet(sourceSets.main.get())
            sourceSet(sourceSets.getByName("client"))
        }
    }

    runConfigs.all {
        preferGradleTask = true
        // Shared between versions so worlds and configs survive switching target.
        runDirectory = rootProject.file("run")

        // ./gradlew :<target>:runServer -PdumpTradeCatalog regenerates the
        // bundled pre-26 trade catalogue into run/vtp-trade-catalog.json.
        if (project.hasProperty("dumpTradeCatalog")) {
            jvmArguments.add("-Dvtp.dumpTradeCatalog=true")
        }
    }
}

java {
    withSourcesJar()
    sourceCompatibility = requiredJava
    targetCompatibility = requiredJava

    toolchain {
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        // Fixes special characters in the locale files being mangled by whatever
        // the platform default encoding happens to be.
        options.encoding = "UTF-8"
    }

    // Every resource-processing task, not just processResources.
    // splitEnvironmentSourceSets gives the client source set its own
    // processClientResources, and the client mixin config lives there.
    // Configuring only processResources shipped that file with a literal
    // placeholder, which Mixin rejects at launch before anything else loads.
    withType<ProcessResources>().configureEach {
        val props = mapOf(
            "id" to modId,
            "name" to modName,
            "version" to project.version.toString(),
            "minecraft" to mcCompat,
            "loader_version" to loaderVersion,
            "java" to "JAVA_${requiredJava.majorVersion}",
            // VillagerTrade only exists from 26 on, so the accessor targeting it
            // is compiled away below that -- and a mixin config naming a missing
            // class is a hard failure at load.
            "villager_trade_mixin" to villagerTradeMixinEntry,
            "glow_mixin" to glowMixinEntry,
        )
        props.forEach { (key, value) -> inputs.property(key, value) }

        // The generated trade catalogue is only read below 26; from 26 on the
        // server reads the real registry, so shipping it there is dead weight.
        if (sc.current.parsed >= "26.1") {
            exclude("data/villager_trade_scanner/trade_catalog.json")
        }

        filesMatching("fabric.mod.json") { expand(props) }
        filesMatching("*.mixins.json") { expand(props) }
    }

    jar {
        from(rootProject.file("LICENSE.txt")) {
            // The Fabric template shipped this pointing at "LICENSE", a name this
            // project has never had, so the jar silently bundled no licence.
            rename { "LICENSE_$archivesName" }
        }
    }

    register("buildAndCollect") {
        group = "build"
        description = "Builds this target into build/libs/<mod version>/, one jar per Minecraft release."

        // CI runs buildAll, which reaches the jars through this task. Without
        // this the locale and placeholder checks never ran in CI at all.
        dependsOn("check")

        val builtJar = loomx.modJar.flatMap { it.archiveFile }
        val builtSources = loomx.modSourcesJar.flatMap { it.archiveFile }
        val outDir = rootProject.layout.buildDirectory.dir("libs/$modVersion")

        // Captured at configuration time; the action must not touch the project.
        val releases = compatibleVersions
        val builtVersion = "$modVersion+mc${sc.current.version}"
        val prefix = "$archivesName-$modVersion+mc"

        inputs.files(builtJar, builtSources)
        outputs.dir(outDir)

        doLast {
            val dir = outDir.get().asFile
            dir.mkdirs()

            // One jar per Minecraft release rather than one per API generation.
            // The builds are identical -- a single jar already declares every
            // version it supports --
            // but a GitHub release is a flat list of files, and someone on
            // 1.21.6 should not have to work out that the file named 1.21.8 is
            // the one for them.
            //
            // The version inside is rewritten to match the name. Copying alone
            // would leave every one of them reporting the build's own version in
            // the mod list, which just moves the confusion somewhere less
            // visible.
            for (mc in releases) {
                writeJarWithVersion(
                    builtJar.get().asFile,
                    File(dir, "$prefix$mc.jar"),
                    builtVersion,
                    "$modVersion+mc$mc",
                )
            }

            // Sources are the same for the whole generation, so one copy under
            // the version it was built against is enough.
            builtSources.get().asFile.copyTo(
                File(dir, "$prefix${sc.current.version}-sources.jar"), overwrite = true)
        }
    }
}


/**
 * Copies a jar, replacing the mod version recorded inside it.
 *
 * <p>Only fabric.mod.json is touched, and only the exact version string the
 * build put there, so nothing else in the archive can be disturbed.
 */
fun writeJarWithVersion(source: File, target: File, from: String, to: String) {
    ZipFile(source).use { zip ->
        ZipOutputStream(target.outputStream().buffered()).use { out ->
            for (entry in zip.entries()) {
                out.putNextEntry(ZipEntry(entry.name))
                if (!entry.isDirectory) {
                    if (entry.name == "fabric.mod.json") {
                        val text = zip.getInputStream(entry).use {
                            it.readBytes().toString(Charsets.UTF_8)
                        }
                        out.write(text.replace("\"$from\"", "\"$to\"").toByteArray(Charsets.UTF_8))
                    } else {
                        zip.getInputStream(entry).use { it.copyTo(out) }
                    }
                }
                out.closeEntry()
            }
        }
    }
}

// Locale files are the easiest place in this mod to ship a crash. A missing key
// renders as a raw `vtp.…` id, and a format-specifier count that differs from
// en_us throws at format time -- in front of the player, not at build time. So
// this runs on every build rather than living as a script someone remembers.
tasks.register("checkLocales") {
    description = "Verifies every locale has en_us's key set and matching format specifiers."
    group = "verification"

    val langDir = rootProject.file("src/main/resources/assets/villager_trade_scanner/lang")
    inputs.dir(langDir)

    doLast {
        val slurper = groovy.json.JsonSlurper()

        @Suppress("UNCHECKED_CAST")
        fun read(f: File) = slurper.parse(f, "UTF-8") as Map<String, String>

        // Java format specifiers, positional (%3$s) included; %% is a literal.
        val specifier = Regex("""%(?:\d+\$)?[a-zA-Z]""")
        fun count(s: String) = specifier.findAll(s.replace("%%", "")).count()

        val base = read(File(langDir, "en_us.json"))
        val locales = langDir.listFiles()
            .orEmpty()
            .filter { it.name.endsWith(".json") && it.name != "en_us.json" }
            .sortedBy { it.name }

        val problems = buildList {
            for (f in locales) {
                val loc = read(f)
                (base.keys - loc.keys).forEach { add("${f.name}: missing key '$it'") }
                (loc.keys - base.keys).forEach { add("${f.name}: unknown key '$it'") }
                for (key in base.keys intersect loc.keys) {
                    val want = count(base.getValue(key))
                    val got = count(loc.getValue(key))
                    if (want != got) {
                        add("${f.name}: '$key' has $got format specifier(s), en_us has $want")
                    }
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException("Locale check failed:\n  " + problems.joinToString("\n  "))
        }
        logger.lifecycle("checkLocales: en_us + ${locales.size} locale(s) OK, ${base.size} keys each")
    }
}

tasks.named("check") {
    dependsOn("checkLocales")
}

// A placeholder that survives resource processing is not a build warning, it is
// a crash on the player's machine: Mixin refuses an unrecognised compatibility
// level before any mod loads. That shipped once already, so it is checked.
tasks.register("checkResourcePlaceholders") {
    description = "Fails if a template placeholder survived resource processing."
    group = "verification"

    val processed = tasks.withType<ProcessResources>()
    dependsOn(processed)
    val dirs = processed.map { it.destinationDir }

    doLast {
        val placeholder = Regex("""\${'$'}\{[A-Za-z_][A-Za-z0-9_]*}""")
        val problems = buildList {
            for (dir in dirs) {
                if (!dir.isDirectory) continue
                dir.walkTopDown()
                    .filter { it.isFile && it.extension == "json" }
                    .forEach { file ->
                        placeholder.find(file.readText())?.let {
                            add("${file.relativeTo(dir)}: ${it.value}")
                        }
                    }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Unexpanded placeholders in processed resources:\n  " + problems.joinToString("\n  "))
        }
        logger.lifecycle("checkResourcePlaceholders: ${dirs.size} resource dir(s) clean")
    }
}

tasks.named("check") {
    dependsOn("checkResourcePlaceholders")
}
