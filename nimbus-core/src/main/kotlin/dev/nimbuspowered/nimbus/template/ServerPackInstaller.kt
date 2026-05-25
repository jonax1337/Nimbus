package dev.nimbuspowered.nimbus.template

import dev.nimbuspowered.nimbus.config.ServerSoftware
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name

internal class ServerPackInstaller {

    private val logger = LoggerFactory.getLogger(ServerPackInstaller::class.java)

    fun isServerPack(zipPath: Path): Boolean {
        if (!zipPath.name.endsWith(".zip")) return false
        return try {
            ZipFile(zipPath.toFile()).use { zip ->
                val entries = zip.entries().toList().map { it.name }
                val hasMods = entries.any { it.startsWith("mods/") && it.endsWith(".jar") }
                val hasStartup = entries.any {
                    it == "startserver.sh" || it == "startserver.bat" ||
                    it == "run.sh" || it == "run.bat" ||
                    it == "start.sh" || it == "start.bat" ||
                    it.matches(Regex("""(neo)?forge-[\d.]+-installer\.jar""")) ||
                    it == "fabric-server-launch.jar"
                }
                hasMods && hasStartup
            }
        } catch (e: Exception) {
            false
        }
    }

    fun getInfo(zipPath: Path): ModpackInfo? {
        return try {
            ZipFile(zipPath.toFile()).use { zip ->
                val entries = zip.entries().toList()
                val entryNames = entries.map { it.name }

                val modCount = entryNames.count { it.startsWith("mods/") && it.endsWith(".jar") && it.count { c -> c == '/' } == 1 }

                val (modloader, loaderVersion, mcVersion) = detectFromServerPack(zip, entries, entryNames)

                val packName = zipPath.name
                    .removeSuffix(".zip")
                    .replace(Regex("[-_]?[Ss]erver[Ff]iles[-_]?"), "")
                    .replace(Regex("[-_]?[Ss]erver[-_]?[Pp]ack[-_]?"), "")
                    .ifEmpty { zipPath.name.removeSuffix(".zip") }

                val versionMatch = Regex("""[\-_](\d+\.\d+(?:\.\d+)?)""").find(zipPath.name)
                val packVersion = versionMatch?.groupValues?.get(1) ?: ""

                ModpackInfo(
                    name = packName,
                    version = packVersion,
                    mcVersion = mcVersion,
                    modloader = modloader,
                    modloaderVersion = loaderVersion,
                    totalFiles = modCount,
                    serverFiles = modCount,
                    source = ModpackSource.SERVER_PACK
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to analyze server pack: {}", e.message)
            null
        }
    }

    fun extract(zipPath: Path, templateDir: Path) {
        val normalizedTarget = templateDir.normalize()
        ZipFile(zipPath.toFile()).use { zip ->
            val entries = zip.entries().toList()
            for (entry in entries) {
                if (entry.isDirectory) continue

                val name = entry.name
                if (name.matches(Regex("""(neo)?forge-[\d.]+-installer\.jar""")) ||
                    name == "startserver.sh" || name == "startserver.bat" ||
                    name == "run.sh" || name == "run.bat" ||
                    name == "start.sh" || name == "start.bat" ||
                    name == "user_jvm_args.txt" ||
                    name == "README.txt" || name == "README.md") continue

                val target = templateDir.resolve(name).normalize()
                if (!target.startsWith(normalizedTarget)) {
                    logger.warn("Path traversal blocked in server pack: {}", name)
                    continue
                }

                val parent = target.parent
                if (!parent.exists()) parent.createDirectories()
                zip.getInputStream(entry).use { input ->
                    Files.write(target, input.readBytes())
                }
            }
        }
    }

    private fun detectFromServerPack(
        zip: ZipFile,
        entries: List<java.util.zip.ZipEntry>,
        entryNames: List<String>
    ): Triple<ServerSoftware, String, String> {
        // 1. Prefer ServerPackCreator manifest.json — authoritative when present.
        //    Format: { "minecraftVersion": "1.21.1", "modloader": "NeoForge",
        //              "modloaderVersion": "21.1.230", "serverPackCreatorVersion": ... }
        detectFromManifestJson(zip, entries)?.let { return it }

        val neoforgeInstaller = entryNames.firstOrNull { it.matches(Regex("""neoforge-[\d.]+-installer\.jar""")) }
        if (neoforgeInstaller != null) {
            val nfVersion = Regex("""neoforge-([\d.]+)-installer\.jar""").find(neoforgeInstaller)?.groupValues?.get(1) ?: ""
            val mcVer = detectMcVersionFromNeoForge(nfVersion) ?: detectMcVersionFromScripts(zip, entries) ?: "unknown"
            return Triple(ServerSoftware.NEOFORGE, nfVersion, mcVer)
        }

        val forgeInstaller = entryNames.firstOrNull { it.matches(Regex("""forge-[\d.]+-[\d.]+-installer\.jar""")) }
        if (forgeInstaller != null) {
            val match = Regex("""forge-([\d.]+)-([\d.]+)-installer\.jar""").find(forgeInstaller)
            val mcVer = match?.groupValues?.get(1) ?: "unknown"
            val forgeVer = match?.groupValues?.get(2) ?: ""
            return Triple(ServerSoftware.FORGE, forgeVer, mcVer)
        }

        val hasFabric = entryNames.any { it == "fabric-server-launch.jar" }
        if (hasFabric) {
            val mcVer = detectMcVersionFromScripts(zip, entries) ?: "unknown"
            return Triple(ServerSoftware.FABRIC, "", mcVer)
        }

        val mcVer = detectMcVersionFromScripts(zip, entries) ?: "unknown"

        for (scriptName in listOf("startserver.sh", "startserver.bat", "run.sh", "run.bat", "start.sh", "variables.txt")) {
            val scriptEntry = entries.firstOrNull { it.name == scriptName } ?: continue
            val content = zip.getInputStream(scriptEntry).bufferedReader().readText()

            if (content.contains("NEOFORGE", ignoreCase = true) || content.contains("neoforge", ignoreCase = false)) {
                val ver = Regex("""NEOFORGE_VERSION[=:]?\s*(\S+)""", RegexOption.IGNORE_CASE).find(content)?.groupValues?.get(1) ?: ""
                return Triple(ServerSoftware.NEOFORGE, ver, mcVer)
            }
            if (content.contains("FORGE", ignoreCase = true) && !content.contains("NEOFORGE", ignoreCase = true)) {
                val ver = Regex("""FORGE_VERSION[=:]?\s*(\S+)""", RegexOption.IGNORE_CASE).find(content)?.groupValues?.get(1) ?: ""
                return Triple(ServerSoftware.FORGE, ver, mcVer)
            }
            if (content.contains("fabric", ignoreCase = true)) {
                return Triple(ServerSoftware.FABRIC, "", mcVer)
            }
        }

        return Triple(ServerSoftware.CUSTOM, "", mcVer)
    }

    /**
     * Reads a ServerPackCreator-generated manifest.json from the ZIP root.
     * Returns null when no manifest exists or its fields can't be parsed.
     * Authoritative when present — the pack author or ServerPackCreator wrote it
     * directly, so it overrides JAR-filename / script heuristics.
     */
    private fun detectFromManifestJson(
        zip: ZipFile,
        entries: List<java.util.zip.ZipEntry>,
    ): Triple<ServerSoftware, String, String>? {
        val manifestEntry = entries.firstOrNull { it.name == "manifest.json" } ?: return null
        return try {
            val content = zip.getInputStream(manifestEntry).bufferedReader().readText()
            val mcMatch = Regex(""""minecraftVersion"\s*:\s*"([^"]+)"""").find(content) ?: return null
            val loaderMatch = Regex(""""modloader"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(content)
            val versionMatch = Regex(""""modloaderVersion"\s*:\s*"([^"]+)"""").find(content)

            val mcVer = mcMatch.groupValues[1]
            val loaderName = loaderMatch?.groupValues?.get(1)?.lowercase() ?: ""
            val loaderVer = versionMatch?.groupValues?.get(1) ?: ""

            val software = when {
                loaderName.contains("neoforge") -> ServerSoftware.NEOFORGE
                loaderName.contains("forge") -> ServerSoftware.FORGE
                loaderName.contains("fabric") -> ServerSoftware.FABRIC
                else -> return null  // unknown loader → let heuristics try
            }
            Triple(software, loaderVer, mcVer)
        } catch (e: Exception) {
            logger.warn("Could not parse manifest.json: {}", e.message)
            null
        }
    }

    private fun detectMcVersionFromNeoForge(nfVersion: String): String? {
        val major = nfVersion.split(".").firstOrNull()?.toIntOrNull() ?: return null
        val minor = nfVersion.split(".").getOrNull(1)?.toIntOrNull() ?: 0
        return when {
            major >= 20 -> {
                if (minor > 0) "1.$major.$minor" else "1.$major"
            }
            else -> null
        }
    }

    private fun detectMcVersionFromScripts(zip: ZipFile, entries: List<java.util.zip.ZipEntry>): String? {
        for (scriptName in listOf("startserver.sh", "startserver.bat", "run.sh", "run.bat", "variables.txt", "server.properties")) {
            val entry = entries.firstOrNull { it.name == scriptName } ?: continue
            val content = zip.getInputStream(entry).bufferedReader().readText()

            val mcPattern = Regex("""(?:Minecraft|mc)\s*(\d+\.\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            mcPattern.find(content)?.groupValues?.get(1)?.let { return it }

            val argsPath = Regex("""libraries/net/neoforged/neoforge/([\d.]+)/""").find(content)
            if (argsPath != null) {
                val nfVer = argsPath.groupValues[1]
                detectMcVersionFromNeoForge(nfVer)?.let { return it }
            }

            val forgePath = Regex("""libraries/net/minecraftforge/forge/(\d+\.\d+(?:\.\d+)?)-""").find(content)
            if (forgePath != null) return forgePath.groupValues[1]
        }
        return null
    }
}
