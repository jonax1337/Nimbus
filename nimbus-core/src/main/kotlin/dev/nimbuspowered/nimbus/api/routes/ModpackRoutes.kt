package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.ApiError
import dev.nimbuspowered.nimbus.api.CreateGroupRequest
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.config.CurseForgeConfig
import dev.nimbuspowered.nimbus.config.GroupType
import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.template.ModpackInstaller
import dev.nimbuspowered.nimbus.template.ModpackSource
import dev.nimbuspowered.nimbus.template.SoftwareResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ChunkedUploadInit(
    val uploadId: String,
    val totalChunks: Int
)

@Serializable
data class ChunkedUploadStatus(
    val uploadId: String,
    val receivedChunks: Int,
    val totalChunks: Int,
    val complete: Boolean
)

/**
 * Tracks active chunked uploads. Entries are cleaned up on finalize or after timeout.
 */
internal data class ChunkedUploadState(
    val uploadId: String,
    val filePath: Path,
    val totalChunks: Int,
    val target: String = "group",   // "group" or "dedicated"
    val receivedChunks: MutableSet<Int> = mutableSetOf(),
    val createdAt: Long = System.currentTimeMillis()
)

internal val chunkedUploads = ConcurrentHashMap<String, ChunkedUploadState>()

/** Sanitize a user-provided file name to prevent path traversal (C3 fix). */
private fun sanitizeFileName(raw: String): String? {
    if (raw.contains("..") || raw.contains('/') || raw.contains('\\')) return null
    val safe = java.io.File(raw).name
    if (safe.isBlank() || safe.startsWith(".")) return null
    return safe
}

@Serializable
data class ModpackResolveRequest(
    val source: String
)

@Serializable
data class ModpackInfoResponse(
    val name: String,
    val version: String,
    val mcVersion: String,
    val modloader: String,
    val modloaderVersion: String,
    val totalFiles: Int,
    val serverFiles: Int,
    val source: String = "MODRINTH"
)

@Serializable
data class ModpackImportRequest(
    val source: String,
    val groupName: String,
    val type: String = "DYNAMIC",
    val memory: String = "2G",
    val minInstances: Int = 1,
    val maxInstances: Int = 2
)

@Serializable
data class ModpackImportResponse(
    val success: Boolean,
    val message: String,
    val groupName: String = "",
    val filesDownloaded: Int = 0,
    val filesFailed: Int = 0
)

fun Route.modpackRoutes(
    softwareResolver: SoftwareResolver,
    groupManager: GroupManager,
    serviceManager: ServiceManager,
    groupsDir: Path,
    templatesDir: Path,
    curseForgeConfig: CurseForgeConfig = CurseForgeConfig()
) {
    val installer = ModpackInstaller(HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 300_000
            socketTimeoutMillis = 30_000
        }
    }, curseForgeConfig.apiKey)
    val maxUploadBytes = 2L * 1024 * 1024 * 1024 // 2 GB for modpack ZIPs

    route("/api/modpacks") {

        // POST /api/modpacks/resolve — Inspect a modpack without importing
        post("resolve") {
            val request = call.receive<ModpackResolveRequest>()
            val downloadDir = templatesDir.resolve(".modpack-cache")
            Files.createDirectories(downloadDir)

            val resolvedPath = installer.resolve(request.source, downloadDir)
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Could not resolve modpack '${request.source}'", ApiError.MODPACK_NOT_FOUND))

            // Server pack ZIP (CurseForge-style)
            if (installer.isServerPack(resolvedPath)) {
                val info = installer.getServerPackInfo(resolvedPath)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Could not analyze server pack", ApiError.MODPACK_INVALID))
                return@post call.respond(ModpackInfoResponse(
                    name = info.name,
                    version = info.version,
                    mcVersion = info.mcVersion,
                    modloader = info.modloader.name,
                    modloaderVersion = info.modloaderVersion,
                    totalFiles = info.totalFiles,
                    serverFiles = info.serverFiles,
                    source = info.source.name
                ))
            }

            // Modrinth .mrpack
            val index = installer.parseIndex(resolvedPath)
                ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid .mrpack file", ApiError.MODPACK_INVALID))

            val info = installer.getInfo(index)
            call.respond(ModpackInfoResponse(
                name = info.name,
                version = info.version,
                mcVersion = info.mcVersion,
                modloader = info.modloader.name,
                modloaderVersion = info.modloaderVersion,
                totalFiles = info.totalFiles,
                serverFiles = info.serverFiles,
                source = info.source.name
            ))
        }

        // POST /api/modpacks/import — Full modpack import (Modrinth slug/URL or CurseForge slug/URL)
        post("import") {
            val request = call.receive<ModpackImportRequest>()

            if (request.groupName.isBlank() || !request.groupName.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid group name", ApiError.VALIDATION_FAILED))
            }
            if (groupManager.getGroup(request.groupName) != null) {
                return@post call.respond(HttpStatusCode.Conflict, apiError("Group '${request.groupName}' already exists", ApiError.GROUP_ALREADY_EXISTS))
            }

            val downloadDir = templatesDir.resolve(".modpack-cache")
            Files.createDirectories(downloadDir)

            val resolvedPath = installer.resolve(request.source, downloadDir)
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Could not resolve modpack '${request.source}'", ApiError.MODPACK_NOT_FOUND))

            val templateName = request.groupName.lowercase()
            val templateDir = templatesDir.resolve(templateName)
            Files.createDirectories(templateDir)

            // Server pack ZIP path
            if (installer.isServerPack(resolvedPath)) {
                return@post handleServerPackImport(
                    call, installer, softwareResolver, groupManager,
                    resolvedPath, request.groupName, templateDir, groupsDir,
                    request.type, request.memory, request.minInstances, request.maxInstances
                )
            }

            // Modrinth .mrpack path
            val index = installer.parseIndex(resolvedPath)
                ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid .mrpack file", ApiError.MODPACK_INVALID))

            val info = installer.getInfo(index)

            // Download modloader JAR
            softwareResolver.ensureJarAvailable(info.modloader, info.mcVersion, templateDir, info.modloaderVersion)

            // Install mod files
            val result = installer.installFiles(index, templateDir) { _, _, _ -> }

            // Extract overrides
            installer.extractOverrides(resolvedPath, templateDir)

            // Install proxy forwarding mods
            when (info.modloader) {
                ServerSoftware.FABRIC -> softwareResolver.ensureFabricProxyMod(templateDir, info.mcVersion)
                ServerSoftware.FORGE, ServerSoftware.NEOFORGE -> softwareResolver.ensureForwardingMod(info.modloader, info.mcVersion, templateDir)
                else -> {}
            }

            // Auto-accept EULA
            templateDir.resolve("eula.txt").toFile().writeText("eula=true\n")

            // Create group
            val groupRequest = CreateGroupRequest(
                name = request.groupName,
                type = request.type,
                template = templateName,
                software = info.modloader.name,
                version = info.mcVersion,
                modloaderVersion = info.modloaderVersion,
                memory = request.memory,
                minInstances = request.minInstances,
                maxInstances = request.maxInstances
            )
            val groupType = try { GroupType.valueOf(request.type.uppercase()) } catch (_: Exception) { GroupType.DYNAMIC }
            val toml = buildGroupToml(groupRequest, groupType, info.modloader)
            groupsDir.resolve("${templateName}.toml").toFile().writeText(toml)
            val groupConfig = buildGroupConfig(groupRequest, groupType, info.modloader)
            groupManager.reloadGroups(
                groupManager.getAllGroups().map { it.config } + groupConfig
            )

            call.respond(HttpStatusCode.Created, ModpackImportResponse(
                success = result.success,
                message = if (result.success) "Modpack '${info.name}' imported as group '${request.groupName}'"
                         else "Import completed with ${result.filesFailed} failed downloads",
                groupName = request.groupName,
                filesDownloaded = result.filesDownloaded,
                filesFailed = result.filesFailed
            ))
        }

        // POST /api/modpacks/upload?groupName=X&type=STATIC&memory=4G — Upload a server pack ZIP
        // File is sent as raw request body (application/octet-stream) to avoid multipart buffering OOM.
        // Query params: groupName (required), type, memory, minInstances, maxInstances, fileName
        post("upload") {
            val groupName = call.request.queryParameters["groupName"] ?: ""
            val type = call.request.queryParameters["type"] ?: "DYNAMIC"
            val memory = call.request.queryParameters["memory"] ?: "2G"
            val minInstances = call.request.queryParameters["minInstances"]?.toIntOrNull() ?: 1
            val maxInstances = call.request.queryParameters["maxInstances"]?.toIntOrNull() ?: 2
            val rawFileName = call.request.queryParameters["fileName"] ?: "upload.zip"

            if (groupName.isBlank() || !groupName.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid group name", ApiError.VALIDATION_FAILED))
            }
            if (groupManager.getGroup(groupName) != null) {
                return@post call.respond(HttpStatusCode.Conflict, apiError("Group '$groupName' already exists", ApiError.GROUP_ALREADY_EXISTS))
            }

            // C3 fix: sanitize fileName to prevent path traversal
            val fileName = sanitizeFileName(rawFileName)
                ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid file name", ApiError.VALIDATION_FAILED))

            // Stream request body directly to disk — no memory buffering
            val uploadDir = templatesDir.resolve(".modpack-uploads")
            Files.createDirectories(uploadDir)
            val uploadedZip = uploadDir.resolve(fileName)

            // Verify resolved path stays inside upload directory
            if (!uploadedZip.toFile().canonicalPath.startsWith(uploadDir.toFile().canonicalPath + java.io.File.separator)) {
                return@post call.respond(HttpStatusCode.BadRequest, apiError("Path traversal detected", ApiError.PATH_TRAVERSAL))
            }

            try {
                call.receiveStream().use { input ->
                    java.io.FileOutputStream(uploadedZip.toFile()).use { output ->
                        val buf = ByteArray(65536)
                        var totalWritten = 0L
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            totalWritten += read
                            if (totalWritten > maxUploadBytes) {
                                output.close()
                                Files.deleteIfExists(uploadedZip)
                                return@post call.respond(HttpStatusCode.PayloadTooLarge,
                                    apiError("File too large (max ${maxUploadBytes / 1024 / 1024}MB)", ApiError.PAYLOAD_TOO_LARGE))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Files.deleteIfExists(uploadedZip)
                return@post call.respond(HttpStatusCode.InternalServerError,
                    apiError("Upload failed: ${e.message}", ApiError.MODPACK_UPLOAD_FAILED))
            }

            val templateName = groupName.lowercase()
            val templateDir = templatesDir.resolve(templateName)
            Files.createDirectories(templateDir)

            // Detect format: server pack ZIP or .mrpack
            if (installer.isServerPack(uploadedZip)) {
                handleServerPackImport(
                    call, installer, softwareResolver, groupManager,
                    uploadedZip, groupName, templateDir, groupsDir,
                    type, memory, minInstances, maxInstances
                )
            } else if (uploadedZip.fileName.toString().endsWith(".mrpack") || installer.parseIndex(uploadedZip) != null) {
                // Treat as .mrpack
                val index = installer.parseIndex(uploadedZip)
                if (index == null) {
                    Files.deleteIfExists(uploadedZip)
                    return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid modpack file — not a server pack ZIP or .mrpack", ApiError.MODPACK_INVALID))
                }
                val info = installer.getInfo(index)

                softwareResolver.ensureJarAvailable(info.modloader, info.mcVersion, templateDir, info.modloaderVersion)
                val result = installer.installFiles(index, templateDir) { _, _, _ -> }
                installer.extractOverrides(uploadedZip, templateDir)

                when (info.modloader) {
                    ServerSoftware.FABRIC -> softwareResolver.ensureFabricProxyMod(templateDir, info.mcVersion)
                    ServerSoftware.FORGE, ServerSoftware.NEOFORGE -> softwareResolver.ensureForwardingMod(info.modloader, info.mcVersion, templateDir)
                    else -> {}
                }

                templateDir.resolve("eula.txt").toFile().writeText("eula=true\n")

                val groupRequest = CreateGroupRequest(
                    name = groupName, type = type, template = templateName,
                    software = info.modloader.name, version = info.mcVersion,
                    modloaderVersion = info.modloaderVersion, memory = memory,
                    minInstances = minInstances, maxInstances = maxInstances
                )
                val groupType = try { GroupType.valueOf(type.uppercase()) } catch (_: Exception) { GroupType.DYNAMIC }
                val toml = buildGroupToml(groupRequest, groupType, info.modloader)
                groupsDir.resolve("${templateName}.toml").toFile().writeText(toml)
                val groupConfig = buildGroupConfig(groupRequest, groupType, info.modloader)
                groupManager.reloadGroups(groupManager.getAllGroups().map { it.config } + groupConfig)

                Files.deleteIfExists(uploadedZip)
                call.respond(HttpStatusCode.Created, ModpackImportResponse(
                    success = result.success,
                    message = if (result.success) "Modpack '${info.name}' uploaded and imported as group '$groupName'"
                             else "Import completed with ${result.filesFailed} failed downloads",
                    groupName = groupName,
                    filesDownloaded = result.filesDownloaded,
                    filesFailed = result.filesFailed
                ))
            } else {
                Files.deleteIfExists(uploadedZip)
                call.respond(HttpStatusCode.BadRequest, apiError("Uploaded file is not a valid server pack ZIP or .mrpack", ApiError.MODPACK_INVALID))
            }
        }

        // ── Chunked upload ────────────────────────────────────────────
        // POST /api/modpacks/upload/init?fileName=X&totalChunks=N
        // Returns an uploadId. Chunks are then sent individually and finalized.
        post("upload/init") {
            val rawFileName = call.request.queryParameters["fileName"] ?: "upload.zip"
            val totalChunks = call.request.queryParameters["totalChunks"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Missing totalChunks parameter", ApiError.VALIDATION_FAILED))
            val target = (call.request.queryParameters["target"] ?: "group").lowercase()

            if (totalChunks < 1 || totalChunks > 100_000) {
                return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid totalChunks value", ApiError.VALIDATION_FAILED))
            }
            if (target !in setOf("group", "dedicated")) {
                return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid target '$target' (expected 'group' or 'dedicated')", ApiError.VALIDATION_FAILED))
            }

            // C3 fix: sanitize fileName to prevent path traversal
            val fileName = sanitizeFileName(rawFileName)
                ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid file name", ApiError.VALIDATION_FAILED))

            val uploadId = java.util.UUID.randomUUID().toString()
            val uploadDir = templatesDir.resolve(".modpack-uploads")
            Files.createDirectories(uploadDir)
            val filePath = uploadDir.resolve("$uploadId-$fileName")

            // Verify resolved path stays inside upload directory
            if (!filePath.toFile().canonicalPath.startsWith(uploadDir.toFile().canonicalPath + java.io.File.separator)) {
                return@post call.respond(HttpStatusCode.BadRequest, apiError("Path traversal detected", ApiError.PATH_TRAVERSAL))
            }

            // Create empty file
            Files.createFile(filePath)

            chunkedUploads[uploadId] = ChunkedUploadState(
                uploadId = uploadId,
                filePath = filePath,
                totalChunks = totalChunks,
                target = target
            )

            // Clean up stale in-memory entries + orphan files older than 1h (single-shot
            // uploads or aborted streams leave UUID-prefixed leftovers behind).
            pruneStaleUploads(uploadDir, uploadId)

            call.respond(ChunkedUploadInit(uploadId = uploadId, totalChunks = totalChunks))
        }

        // POST /api/modpacks/upload/chunk?uploadId=X&index=N
        // Body: raw chunk bytes (application/octet-stream)
        post("upload/chunk") {
            val uploadId = call.request.queryParameters["uploadId"] ?: ""
            val chunkIndex = call.request.queryParameters["index"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Missing index parameter", ApiError.VALIDATION_FAILED))

            val state = chunkedUploads[uploadId]
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Upload not found or expired", ApiError.CHUNKED_UPLOAD_NOT_FOUND))

            if (chunkIndex < 0 || chunkIndex >= state.totalChunks) {
                return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid chunk index", ApiError.CHUNKED_UPLOAD_INVALID))
            }

            // Chunks must arrive in order for simple file append
            // If out of order, reject — the client should send sequentially
            val expectedIndex = state.receivedChunks.size
            if (chunkIndex != expectedIndex) {
                return@post call.respond(HttpStatusCode.BadRequest,
                    apiError("Expected chunk $expectedIndex, got $chunkIndex. Chunks must be sent in order.", ApiError.CHUNKED_UPLOAD_INVALID))
            }

            try {
                call.receiveStream().use { input ->
                    Files.newOutputStream(state.filePath, StandardOpenOption.APPEND).use { output ->
                        val buf = ByteArray(65536)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                        }
                    }
                }
                state.receivedChunks.add(chunkIndex)
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.InternalServerError,
                    apiError("Failed to write chunk: ${e.message}", ApiError.MODPACK_UPLOAD_FAILED))
            }

            call.respond(ChunkedUploadStatus(
                uploadId = uploadId,
                receivedChunks = state.receivedChunks.size,
                totalChunks = state.totalChunks,
                complete = state.receivedChunks.size == state.totalChunks
            ))
        }

        // POST /api/modpacks/upload/finalize?uploadId=X&groupName=X&type=X&memory=X&...
        // Triggers the same import logic as the single-shot upload endpoint.
        // Note: only handles target="group" uploads. Dedicated chunked uploads
        // go to /api/dedicated/modpack/upload/finalize and reuse `chunkedUploads`.
        post("upload/finalize") {
            val uploadId = call.request.queryParameters["uploadId"] ?: ""
            val groupName = call.request.queryParameters["groupName"] ?: ""
            val type = call.request.queryParameters["type"] ?: "DYNAMIC"
            val memory = call.request.queryParameters["memory"] ?: "2G"
            val minInstances = call.request.queryParameters["minInstances"]?.toIntOrNull() ?: 1
            val maxInstances = call.request.queryParameters["maxInstances"]?.toIntOrNull() ?: 2

            val state = chunkedUploads.remove(uploadId)
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Upload not found or expired", ApiError.CHUNKED_UPLOAD_NOT_FOUND))

            if (state.target != "group") {
                // Caller initialised this upload with target=dedicated; route them to
                // the correct finalize endpoint instead of mis-creating a group.
                Files.deleteIfExists(state.filePath)
                return@post call.respond(HttpStatusCode.BadRequest,
                    apiError("Upload was initialised with target='${state.target}'. Use /api/dedicated/modpack/upload/finalize instead.", ApiError.VALIDATION_FAILED))
            }

            if (state.receivedChunks.size != state.totalChunks) {
                Files.deleteIfExists(state.filePath)
                return@post call.respond(HttpStatusCode.BadRequest,
                    apiError("Upload incomplete: ${state.receivedChunks.size}/${state.totalChunks} chunks received", ApiError.CHUNKED_UPLOAD_INVALID))
            }

            if (groupName.isBlank() || !groupName.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                Files.deleteIfExists(state.filePath)
                return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid group name", ApiError.VALIDATION_FAILED))
            }
            if (groupManager.getGroup(groupName) != null) {
                Files.deleteIfExists(state.filePath)
                return@post call.respond(HttpStatusCode.Conflict, apiError("Group '$groupName' already exists", ApiError.GROUP_ALREADY_EXISTS))
            }

            val uploadedZip = state.filePath
            val templateName = groupName.lowercase()
            val templateDir = templatesDir.resolve(templateName)
            Files.createDirectories(templateDir)

            // Same import logic as single-shot upload
            if (installer.isServerPack(uploadedZip)) {
                handleServerPackImport(
                    call, installer, softwareResolver, groupManager,
                    uploadedZip, groupName, templateDir, groupsDir,
                    type, memory, minInstances, maxInstances
                )
            } else if (uploadedZip.fileName.toString().endsWith(".mrpack") || installer.parseIndex(uploadedZip) != null) {
                val index = installer.parseIndex(uploadedZip)
                if (index == null) {
                    Files.deleteIfExists(uploadedZip)
                    return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid modpack file", ApiError.MODPACK_INVALID))
                }
                val info = installer.getInfo(index)

                softwareResolver.ensureJarAvailable(info.modloader, info.mcVersion, templateDir, info.modloaderVersion)
                val result = installer.installFiles(index, templateDir) { _, _, _ -> }
                installer.extractOverrides(uploadedZip, templateDir)

                when (info.modloader) {
                    ServerSoftware.FABRIC -> softwareResolver.ensureFabricProxyMod(templateDir, info.mcVersion)
                    ServerSoftware.FORGE, ServerSoftware.NEOFORGE -> softwareResolver.ensureForwardingMod(info.modloader, info.mcVersion, templateDir)
                    else -> {}
                }

                templateDir.resolve("eula.txt").toFile().writeText("eula=true\n")

                val groupRequest = CreateGroupRequest(
                    name = groupName, type = type, template = templateName,
                    software = info.modloader.name, version = info.mcVersion,
                    modloaderVersion = info.modloaderVersion, memory = memory,
                    minInstances = minInstances, maxInstances = maxInstances
                )
                val groupType = try { GroupType.valueOf(type.uppercase()) } catch (_: Exception) { GroupType.DYNAMIC }
                val toml = buildGroupToml(groupRequest, groupType, info.modloader)
                groupsDir.resolve("${templateName}.toml").toFile().writeText(toml)
                val groupConfig = buildGroupConfig(groupRequest, groupType, info.modloader)
                groupManager.reloadGroups(groupManager.getAllGroups().map { it.config } + groupConfig)

                Files.deleteIfExists(uploadedZip)
                call.respond(HttpStatusCode.Created, ModpackImportResponse(
                    success = result.success,
                    message = if (result.success) "Modpack '${info.name}' uploaded and imported as group '$groupName'"
                             else "Import completed with ${result.filesFailed} failed downloads",
                    groupName = groupName,
                    filesDownloaded = result.filesDownloaded,
                    filesFailed = result.filesFailed
                ))
            } else {
                Files.deleteIfExists(uploadedZip)
                call.respond(HttpStatusCode.BadRequest, apiError("Uploaded file is not a valid server pack ZIP or .mrpack", ApiError.MODPACK_INVALID))
            }
        }
    }
}

/**
 * Cleans up the `.modpack-uploads/` directory: removes in-memory chunked-upload
 * state entries older than 1h, plus any orphan files on disk older than 1h that
 * are NOT referenced by an active state entry. Orphans accumulate when uploads
 * are aborted mid-stream (the cleanup-on-failure path leaves UUID-prefixed
 * leftovers behind on connection drops).
 */
private fun pruneStaleUploads(uploadDir: Path, excludeUploadId: String) {
    val now = System.currentTimeMillis()
    val maxAgeMs = 3_600_000L  // 1 hour

    chunkedUploads.entries.removeIf { (_, state) ->
        val stale = now - state.createdAt > maxAgeMs && state.uploadId != excludeUploadId
        if (stale) Files.deleteIfExists(state.filePath)
        stale
    }

    val activePaths = chunkedUploads.values.map { it.filePath.toAbsolutePath() }.toSet()
    try {
        Files.list(uploadDir).use { stream ->
            stream.forEach { entry ->
                if (entry.toAbsolutePath() in activePaths) return@forEach
                val ageMs = now - Files.getLastModifiedTime(entry).toMillis()
                if (ageMs > maxAgeMs) Files.deleteIfExists(entry)
            }
        }
    } catch (_: Exception) {
        // Best-effort cleanup; surface as logger.warn if desired but never block init.
    }
}

/**
 * Shared handler for server pack ZIP import (used by both /import and /upload).
 */
private suspend fun handleServerPackImport(
    call: io.ktor.server.routing.RoutingCall,
    installer: ModpackInstaller,
    softwareResolver: SoftwareResolver,
    groupManager: GroupManager,
    zipPath: Path,
    groupName: String,
    templateDir: Path,
    groupsDir: Path,
    type: String,
    memory: String,
    minInstances: Int,
    maxInstances: Int
) {
    val info = installer.getServerPackInfo(zipPath)
    if (info == null) {
        Files.deleteIfExists(zipPath)
        return call.respond(HttpStatusCode.BadRequest, apiError("Could not analyze server pack", ApiError.MODPACK_INVALID))
    }

    // Extract all server files to template
    installer.extractServerPack(zipPath, templateDir)

    // Install modloader JAR (the ZIP has the installer but Nimbus needs to run it)
    softwareResolver.ensureJarAvailable(info.modloader, info.mcVersion, templateDir, info.modloaderVersion)

    // Install proxy forwarding mods
    when (info.modloader) {
        ServerSoftware.FABRIC -> softwareResolver.ensureFabricProxyMod(templateDir, info.mcVersion)
        ServerSoftware.FORGE, ServerSoftware.NEOFORGE -> softwareResolver.ensureForwardingMod(info.modloader, info.mcVersion, templateDir)
        else -> {}
    }

    // Auto-accept EULA
    templateDir.resolve("eula.txt").toFile().writeText("eula=true\n")

    // Create group config
    val templateName = groupName.lowercase()
    val groupRequest = CreateGroupRequest(
        name = groupName, type = type, template = templateName,
        software = info.modloader.name, version = info.mcVersion,
        modloaderVersion = info.modloaderVersion, memory = memory,
        minInstances = minInstances, maxInstances = maxInstances
    )
    val groupType = try { GroupType.valueOf(type.uppercase()) } catch (_: Exception) { GroupType.DYNAMIC }
    val toml = buildGroupToml(groupRequest, groupType, info.modloader)
    groupsDir.resolve("${templateName}.toml").toFile().writeText(toml)
    val groupConfig = buildGroupConfig(groupRequest, groupType, info.modloader)
    groupManager.reloadGroups(groupManager.getAllGroups().map { it.config } + groupConfig)

    Files.deleteIfExists(zipPath)
    call.respond(HttpStatusCode.Created, ModpackImportResponse(
        success = true,
        message = "Server pack imported as group '$groupName' (${info.modloader.name} ${info.modloaderVersion}, MC ${info.mcVersion}, ${info.serverFiles} mods)",
        groupName = groupName,
        filesDownloaded = info.serverFiles,
        filesFailed = 0
    ))
}
