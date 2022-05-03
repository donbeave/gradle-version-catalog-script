import java.io.File
import java.io.InputStream
import java.util.*

// CONFIG
val prefix = "libs"
// end CONFIG

data class ModuleVersion(val module: String, val version: String?)

val versions = mutableMapOf<String, String>()
val libraries = mutableMapOf<String, ModuleVersion>()

val versionsMap = mutableMapOf<String, String>()

val camelRegex = "(?<=[a-zA-Z])[A-Z]".toRegex()
val snakeRegex = "_[a-zA-Z]".toRegex()

val dependenciesRegex = ("^(\\s+)(annotationProcessor|api|implementation|compileOnly|testImplementation" +
        "|testCompileOnly" +
        "|testAnnotationProcessor|kaptTest|kapt)[\\s+]?[(]?[\\'\\\"]?(platform\\()?[\\'\\\"]([\\w\\" +
        ".\\:\\-\\\$\\{\\}]+)[\\'\\\"][\\)]?([\\s]+)?").toRegex()

// String extensions
fun String.camelToSnakeCase(): String {
    return camelRegex.replace(this) {
        "-${it.value}"
    }.lowercase(Locale.getDefault())
}

val gradleFiles = mutableListOf<File>()

File(".").walk().forEach { file ->
    if (file.name.startsWith("build.gradle")) {
        gradleFiles.add(file)
    }
}


File("gradle.properties").forEachLine {
    if (it.contains("Version")) {
        val parts = it.split("=")
        val part1 = parts[0].trim().strip()
        val part2 = parts[1].trim().strip()

        versionsMap[part1] = part2
    }
}


gradleFiles.forEach { gradleFile ->
    val inputStream: InputStream = gradleFile.inputStream()
    val lineList = mutableListOf<String>()

    inputStream.bufferedReader().forEachLine {
        if (!dependenciesRegex.matches(it)) {
            lineList.add(it)
            return@forEachLine
        }

        val matchResult = dependenciesRegex.find(it)!!

        val indent = matchResult.groups[1]!!.value
        val type = matchResult.groups[2]!!.value
        val dependencyNotation = matchResult.groups[4]!!.value
        val isPlatform = matchResult.groups[3]?.value == "platform("

        val moduleParts = dependencyNotation.split(":")

        val moduleGroup = moduleParts[0]
        val moduleName = moduleParts[1]

        var moduleAlias = moduleName
            .replace("-interface", "-api")

        if (moduleAlias.endsWith("-bom")) {
            moduleAlias = "boms-${moduleAlias.substring(0, moduleAlias.length - "-bom".length)}"
        }

        moduleAlias = moduleAlias.replace(".", "-")

        if (moduleParts.size == 3) {
            val moduleVersion = moduleParts[2]
            var moduleVersionName = moduleName

            if (moduleVersion.contains("$")) {
                val cleanup = moduleVersion
                    .replace("$", "")
                    .replace("{", "")
                    .replace("}", "")

                moduleVersionName = cleanup
            } else {
                versionsMap[moduleVersionName] = moduleVersion
            }

            if (moduleGroup.startsWith("org.jetbrains.kotlin")) {
                moduleVersionName = "kotlin"
            }

            if (moduleGroup.startsWith("org.testcontainers") &&
                !moduleVersionName.startsWith("testcontainers")) {
                moduleVersionName = "testcontainers-${moduleVersionName}"
            }

            if (moduleGroup.equals("org.graalvm.nativeimage") &&
                moduleName.equals("svm")) {
                moduleVersionName = "graal"
            }

            val moduleVersionNameSnakeCase = moduleVersionName.camelToSnakeCase()
                .replace(".", "-")
                .replace("-interface", "-api")
                .replace("-version", "")

            versions[moduleVersionNameSnakeCase] = versionsMap[moduleVersionName]!!

            libraries[moduleAlias] =
                ModuleVersion(module = "${moduleGroup}:${moduleName}", version = moduleVersionNameSnakeCase)
        } else {
            libraries[moduleAlias] =
                ModuleVersion(module = "${moduleGroup}:${moduleName}", version = null)
        }

        var newLine = "${indent}${type}(libs.${moduleAlias.replace("-", ".")})"

        if (isPlatform) {
            newLine = "${indent}${type}(platform(libs.${moduleAlias.replace("-", ".")}))"
        }

        lineList.add(newLine)
    }

    lineList.add("")

    val text = lineList.joinToString("\n")

    println("Overriding ${gradleFile.absolutePath}")

    gradleFile.writeText(text)
}

val gradleFile = File("gradle/libs.versions.toml")


val versionsMicronautString = versions.entries.filter { it.key.startsWith("micronaut") }.sortedBy {
    it.key
}.joinToString("\n") {
    "${it.key} = \"${it.value}\""
}

val versionsLibsString = versions.entries.filter {
    !it.key.startsWith("micronaut")
}.sortedBy {
    it.key
}.joinToString("\n") {
    "${it.key} = \"${it.value}\""
}

val versionsString = "${versionsLibsString}\n\n" +
        "# Micronaut\n${versionsMicronautString}\n"

val librariesBomsString = libraries.entries.filter { it.key.startsWith("boms-") }.sortedBy {
    it.key
}.joinToString("\n") {
    if (it.value.version == null) {
        "${it.key} = { module = \"${it.value.module}\" }"
    } else {
        "${it.key} = { module = \"${it.value.module}\", version.ref = \"${it.value.version}\" }"
    }
}

val librariesMicronautString = libraries.entries.filter { it.key.startsWith("micronaut") }.sortedBy {
    it.key
}.joinToString("\n") {
    if (it.value.version == null) {
        "${it.key} = { module = \"${it.value.module}\" }"
    } else {
        "${it.key} = { module = \"${it.value.module}\", version.ref = \"${it.value.version}\" }"
    }
}

val librariesOtherString = libraries.entries.filter {
    !it.key.startsWith("boms-") && !it.key.startsWith("micronaut")
}.sortedBy {
    it.key
}.joinToString("\n") {
    if (it.value.version == null) {
        "${it.key} = { module = \"${it.value.module}\" }"
    } else {
        "${it.key} = { module = \"${it.value.module}\", version.ref = \"${it.value.version}\" }"
    }
}

val librariesString = "# BOMs\n${librariesBomsString}\n\n" +
        "# Micronaut\n${librariesMicronautString}\n\n" +
        "${librariesOtherString}"


val text = "[versions]\n${versionsString}\n\n[libraries]\n${librariesString}\n"

println(text)

gradleFile.writeText(text)
