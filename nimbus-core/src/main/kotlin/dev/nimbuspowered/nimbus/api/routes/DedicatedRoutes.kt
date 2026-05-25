package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.*
import dev.nimbuspowered.nimbus.api.ApiError
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.config.CurseForgeConfig
import dev.nimbuspowered.nimbus.config.DedicatedDefinition
import dev.nimbuspowered.nimbus.config.DedicatedServiceConfig
import dev.nimbuspowered.nimbus.config.JvmConfig
import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.service.DedicatedServiceManager
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState
import dev.nimbuspowered.nimbus.template.ModpackInstaller
import dev.nimbuspowered.nimbus.template.SoftwareResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

private val VALID_NAME = Regex("^[a-zA-Z0-9_-]{1,64}$")

fun Route.dedicatedRoutes(
    registry: ServiceRegistry,
    dedicatedServiceManager: DedicatedServiceManager,
    serviceManager: ServiceManager,
    groupManager: GroupManager,
    eventBus: EventBus,
    dedicatedDir: Path,
    softwareResolver: SoftwareResolver? = null,
    templatesDir: Path? = null,
    curseForgeConfig: CurseForgeConfig = CurseForgeConfig()
) {
    route("/api/dedicated") {

        // GET /api/dedicated — List all dedicated configs with runtime status
        get {
            if (!call.requirePermission("nimbus.dashboard.dedicated.view")) return@get
            val responses = dedicatedServiceManager.getAllConfigs().map { config ->
                config.toResponse(registry, dedicatedServiceManager)
            }
            call.respond(DedicatedListResponse(responses, responses.size))
        }

        // GET /api/dedicated/{name} — Single dedicated service detail
        get("{name}") {
            if (!call.requirePermission("nimbus.dashboard.dedicated.view")) return@get
            val name = call.parameters["name"]!!
            val config = dedicatedServiceManager.getConfig(name)
                ?: return@get call.respond(HttpStatusCode.NotFound, apiError("Dedicated service '$name' not found", ApiError.DEDICATED_NOT_FOUND))
            call.respond(config.toResponse(registry, dedicatedServiceManager))
        }

        // POST /api/dedicated — Create a new dedicated service
        post {
            if (!call.requirePermission("nimbus.dashboard.dedicated.manage")) return@post
            val request = call.receive<CreateDedicatedRequest>()

            val errors = validateDedicatedRequest(request)
            if (errors.isNotEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest, apiError(errors.joinToString("; "), ApiError.VALIDATION_FAILED))
            }

            if (dedicatedServiceManager.getConfig(request.name) != null) {
                return@post call.respond(HttpStatusCode.Conflict, apiError("Dedicated service '${request.name}' already exists", ApiError.DEDICATED_ALREADY_EXISTS))
            }

            // Check if port is in use by another dedicated config
            val portConflict = dedicatedServiceManager.getAllConfigs().find { it.dedicated.port == request.port }
            if (portConflict != null) {
                return@post call.respond(HttpStatusCode.Conflict, apiError("Port ${request.port} is already used by dedicated service '${portConflict.dedicated.name}'", ApiError.DEDICATED_PORT_IN_USE))
            }

            val software = ServerSoftware.valueOf(request.software.uppercase())

            val config = DedicatedServiceConfig(
                dedicated = DedicatedDefinition(
                    name = request.name,
                    port = request.port,
                    software = software,
                    version = request.version,
                    jarName = request.jarName,
                    readyPattern = request.readyPattern,
                    javaPath = request.javaPath,
                    proxyEnabled = request.proxyEnabled,
                    memory = request.memory,
                    restartOnCrash = request.restartOnCrash,
                    maxRestarts = request.maxRestarts,
                    jvm = JvmConfig(request.jvmArgs, request.jvmOptimize)
                )
            )

            // Auto-create the managed service directory under paths.dedicated/<name>/
            dedicatedServiceManager.ensureServiceDirectory(request.name, software)
            dedicatedServiceManager.writeTOML(config)
            dedicatedServiceManager.addConfig(config)

            eventBus.emit(NimbusEvent.DedicatedCreated(request.name))
            call.respond(HttpStatusCode.Created, ApiMessage(true, "Dedicated service '${request.name}' created"))
        }

        // PUT /api/dedicated/{name} — Update config (name is immutable, taken from URL)
        put("{name}") {
            if (!call.requirePermission("nimbus.dashboard.dedicated.manage")) return@put
            val name = call.parameters["name"]!!
            val existing = dedicatedServiceManager.getConfig(name)
                ?: return@put call.respond(HttpStatusCode.NotFound, apiError("Dedicated service '$name' not found", ApiError.DEDICATED_NOT_FOUND))

            val request = call.receive<CreateDedicatedRequest>().copy(name = name)

            val errors = validateDedicatedRequest(request)
            if (errors.isNotEmpty()) {
                return@put call.respond(HttpStatusCode.BadRequest, apiError(errors.joinToString("; "), ApiError.VALIDATION_FAILED))
            }

            // Check for port conflict with OTHER dedicated services
            val portConflict = dedicatedServiceManager.getAllConfigs().find {
                it.dedicated.name != name && it.dedicated.port == request.port
            }
            if (portConflict != null) {
                return@put call.respond(HttpStatusCode.Conflict, apiError("Port ${request.port} is already used by dedicated service '${portConflict.dedicated.name}'", ApiError.DEDICATED_PORT_IN_USE))
            }

            // Stop if running — user must manually restart after update
            val running = registry.get(name)
            val wasRunning = running != null && running.state != ServiceState.STOPPED && running.state != ServiceState.CRASHED
            if (wasRunning) {
                serviceManager.stopService(name)
            }

            val software = ServerSoftware.valueOf(request.software.uppercase())

            val config = DedicatedServiceConfig(
                dedicated = DedicatedDefinition(
                    name = name,
                    port = request.port,
                    software = software,
                    version = request.version,
                    jarName = request.jarName,
                    readyPattern = request.readyPattern,
                    javaPath = request.javaPath,
                    proxyEnabled = request.proxyEnabled,
                    memory = request.memory,
                    restartOnCrash = request.restartOnCrash,
                    maxRestarts = request.maxRestarts,
                    jvm = JvmConfig(request.jvmArgs, request.jvmOptimize)
                )
            )

            dedicatedServiceManager.writeTOML(config)
            dedicatedServiceManager.addConfig(config)

            // Proxy-enabled toggle for modded servers: install/remove forwarding mod +
            // patch the server-side forwarding config. Delegates to ServiceFactory so
            // the same logic runs at both edit-time and start-time.
            val proxyChanged = existing.dedicated.proxyEnabled != request.proxyEnabled
            var proxyModMsg: String? = null
            if (proxyChanged && software in setOf(ServerSoftware.FORGE, ServerSoftware.NEOFORGE, ServerSoftware.FABRIC)) {
                val serviceDir = dedicatedServiceManager.getServiceDirectory(name)
                serviceManager.serviceFactory.syncDedicatedProxyForwarding(
                    serviceDir, software, request.version, request.proxyEnabled
                )
                proxyModMsg = if (request.proxyEnabled) "proxy forwarding installed" else "proxy forwarding removed"
            }

            val baseMsg = if (wasRunning) "Dedicated service '$name' updated (was running, stopped — restart to apply)"
                          else "Dedicated service '$name' updated"
            val msg = if (proxyModMsg != null) "$baseMsg, $proxyModMsg" else baseMsg
            call.respond(ApiMessage(true, msg))
        }

        // DELETE /api/dedicated/{name} — Delete
        delete("{name}") {
            if (!call.requirePermission("nimbus.dashboard.dedicated.manage")) return@delete
            val name = call.parameters["name"]!!
            dedicatedServiceManager.getConfig(name)
                ?: return@delete call.respond(HttpStatusCode.NotFound, apiError("Dedicated service '$name' not found", ApiError.DEDICATED_NOT_FOUND))

            // Stop if running
            val running = registry.get(name)
            if (running != null && running.state != ServiceState.STOPPED && running.state != ServiceState.CRASHED) {
                serviceManager.stopService(name)
            }

            dedicatedServiceManager.deleteTOML(name)
            dedicatedServiceManager.removeConfig(name)

            eventBus.emit(NimbusEvent.DedicatedDeleted(name))
            call.respond(ApiMessage(true, "Dedicated service '$name' deleted"))
        }

        // POST /api/dedicated/{name}/start — Start dedicated service
        post("{name}/start") {
            if (!call.requirePermission("nimbus.dashboard.services.start")) return@post
            val name = call.parameters["name"]!!
            val config = dedicatedServiceManager.getConfig(name)
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Dedicated service '$name' not found", ApiError.DEDICATED_NOT_FOUND))

            val existing = registry.get(name)
            if (existing != null && existing.state != ServiceState.STOPPED && existing.state != ServiceState.CRASHED) {
                return@post call.respond(HttpStatusCode.Conflict, apiError("Dedicated service '$name' is already running (state: ${existing.state})", ApiError.DEDICATED_ALREADY_RUNNING))
            }

            val service = serviceManager.startDedicatedService(config.dedicated)
            if (service != null) {
                call.respond(HttpStatusCode.Created, ApiMessage(true, "Dedicated service '${service.name}' starting on port ${service.port}"))
            } else {
                call.respond(HttpStatusCode.InternalServerError, apiError("Failed to start dedicated service '$name'", ApiError.SERVICE_START_FAILED))
            }
        }

        // POST /api/dedicated/{name}/stop — Stop dedicated service
        post("{name}/stop") {
            if (!call.requirePermission("nimbus.dashboard.services.stop")) return@post
            val name = call.parameters["name"]!!
            dedicatedServiceManager.getConfig(name)
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Dedicated service '$name' not found", ApiError.DEDICATED_NOT_FOUND))

            val existing = registry.get(name)
            if (existing == null || existing.state == ServiceState.STOPPED) {
                return@post call.respond(HttpStatusCode.Conflict, apiError("Dedicated service '$name' is not running", ApiError.SERVICE_NOT_READY))
            }

            val stopped = serviceManager.stopService(name)
            if (stopped) {
                call.respond(ApiMessage(true, "Dedicated service '$name' stopped"))
            } else {
                call.respond(HttpStatusCode.InternalServerError, apiError("Failed to stop dedicated service '$name'", ApiError.SERVICE_STOP_FAILED))
            }
        }

        // POST /api/dedicated/{name}/restart — Restart dedicated service
        post("{name}/restart") {
            if (!call.requirePermission("nimbus.dashboard.services.restart")) return@post
            val name = call.parameters["name"]!!
            val config = dedicatedServiceManager.getConfig(name)
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Dedicated service '$name' not found", ApiError.DEDICATED_NOT_FOUND))

            // Stop if running
            val existing = registry.get(name)
            if (existing != null && existing.state != ServiceState.STOPPED && existing.state != ServiceState.CRASHED) {
                serviceManager.stopService(name)
            }

            val service = serviceManager.startDedicatedService(config.dedicated)
            if (service != null) {
                call.respond(ApiMessage(true, "Dedicated service '${service.name}' restarted on port ${service.port}"))
            } else {
                call.respond(HttpStatusCode.InternalServerError, apiError("Failed to restart dedicated service '$name'", ApiError.SERVICE_RESTART_FAILED))
            }
        }

        // Modpack import routes — only available if softwareResolver + templatesDir are provided
        if (softwareResolver != null && templatesDir != null) {
            val installer = ModpackInstaller(HttpClient(CIO) {
                install(HttpTimeout) {
                    connectTimeoutMillis = 10_000
                    requestTimeoutMillis = 300_000
                    socketTimeoutMillis = 30_000
                }
            }, curseForgeConfig.apiKey)
            val maxUploadBytes = 2L * 1024 * 1024 * 1024 // 2 GB

            route("modpack") {

                // POST /api/dedicated/modpack/import — Import modpack from Modrinth/CurseForge URL or slug
                // Body: { source, name, port, memory, proxyEnabled }
                post("import") {
                    if (!call.requirePermission("nimbus.dashboard.dedicated.manage")) return@post
                    val request = call.receive<DedicatedModpackImportRequest>()

                    if (request.name.isBlank() || !request.name.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                        return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid name", ApiError.VALIDATION_FAILED))
                    }
                    if (dedicatedServiceManager.getConfig(request.name) != null) {
                        return@post call.respond(HttpStatusCode.Conflict, apiError("Dedicated service '${request.name}' already exists", ApiError.DEDICATED_ALREADY_EXISTS))
                    }
                    if (request.port < 1 || request.port > 65535) {
                        return@post call.respond(HttpStatusCode.BadRequest, apiError("Port must be between 1 and 65535", ApiError.VALIDATION_FAILED))
                    }

                    val downloadDir = templatesDir.resolve(".modpack-cache")
                    Files.createDirectories(downloadDir)

                    val resolvedPath = installer.resolve(request.source, downloadDir)
                        ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Could not resolve modpack '${request.source}'", ApiError.MODPACK_NOT_FOUND))

                    val serviceDir = dedicatedServiceManager.ensureServiceDirectory(request.name)

                    if (installer.isServerPack(resolvedPath)) {
                        val info = installer.getServerPackInfo(resolvedPath)
                        if (info == null) {
                            return@post call.respond(HttpStatusCode.BadRequest, apiError("Could not analyze server pack", ApiError.MODPACK_INVALID))
                        }
                        installer.extractServerPack(resolvedPath, serviceDir)
                        softwareResolver.ensureJarAvailable(info.modloader, info.mcVersion, serviceDir, info.modloaderVersion)
                        when (info.modloader) {
                            ServerSoftware.FABRIC -> softwareResolver.ensureFabricProxyMod(serviceDir, info.mcVersion)
                            ServerSoftware.FORGE, ServerSoftware.NEOFORGE -> softwareResolver.ensureForwardingMod(info.modloader, info.mcVersion, serviceDir)
                            else -> {}
                        }
                        serviceDir.resolve("eula.txt").toFile().writeText("eula=true\n")

                        val config = DedicatedServiceConfig(
                            dedicated = DedicatedDefinition(
                                name = request.name,
                                port = request.port,
                                software = info.modloader,
                                version = info.mcVersion,
                                jarName = "",
                                readyPattern = "",
                                javaPath = "",
                                proxyEnabled = request.proxyEnabled,
                                memory = request.memory,
                                restartOnCrash = true,
                                maxRestarts = 5,
                                jvm = JvmConfig()
                            )
                        )
                        dedicatedServiceManager.writeTOML(config)
                        dedicatedServiceManager.addConfig(config)
                        eventBus.emit(NimbusEvent.DedicatedCreated(request.name))

                        return@post call.respond(HttpStatusCode.Created, ModpackImportResponse(
                            success = true,
                            message = "Server pack imported as dedicated service '${request.name}' (${info.modloader.name} ${info.modloaderVersion}, MC ${info.mcVersion})",
                            groupName = request.name,
                            filesDownloaded = info.serverFiles,
                            filesFailed = 0
                        ))
                    }

                    // Modrinth .mrpack
                    val index = installer.parseIndex(resolvedPath)
                        ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid .mrpack file", ApiError.MODPACK_INVALID))

                    val info = installer.getInfo(index)
                    softwareResolver.ensureJarAvailable(info.modloader, info.mcVersion, serviceDir, info.modloaderVersion)
                    val result = installer.installFiles(index, serviceDir) { _, _, _ -> }
                    installer.extractOverrides(resolvedPath, serviceDir)
                    when (info.modloader) {
                        ServerSoftware.FABRIC -> softwareResolver.ensureFabricProxyMod(serviceDir, info.mcVersion)
                        ServerSoftware.FORGE, ServerSoftware.NEOFORGE -> softwareResolver.ensureForwardingMod(info.modloader, info.mcVersion, serviceDir)
                        else -> {}
                    }
                    serviceDir.resolve("eula.txt").toFile().writeText("eula=true\n")

                    val config = DedicatedServiceConfig(
                        dedicated = DedicatedDefinition(
                            name = request.name,
                            port = request.port,
                            software = info.modloader,
                            version = info.mcVersion,
                            jarName = "",
                            readyPattern = "",
                            javaPath = "",
                            proxyEnabled = request.proxyEnabled,
                            memory = request.memory,
                            restartOnCrash = true,
                            maxRestarts = 5,
                            jvm = JvmConfig()
                        )
                    )
                    dedicatedServiceManager.writeTOML(config)
                    dedicatedServiceManager.addConfig(config)
                    eventBus.emit(NimbusEvent.DedicatedCreated(request.name))

                    call.respond(HttpStatusCode.Created, ModpackImportResponse(
                        success = result.success,
                        message = if (result.success) "Modpack '${info.name}' imported as dedicated service '${request.name}'"
                                 else "Import completed with ${result.filesFailed} failed downloads",
                        groupName = request.name,
                        filesDownloaded = result.filesDownloaded,
                        filesFailed = result.filesFailed
                    ))
                }

                // POST /api/dedicated/modpack/upload?name=X&port=P&memory=M&proxyEnabled=true&fileName=X
                // Body: raw ZIP / .mrpack stream
                post("upload") {
                    if (!call.requirePermission("nimbus.dashboard.dedicated.manage")) return@post
                    val name = call.request.queryParameters["name"] ?: ""
                    val port = call.request.queryParameters["port"]?.toIntOrNull() ?: 0
                    val memory = call.request.queryParameters["memory"] ?: "4G"
                    val proxyEnabled = call.request.queryParameters["proxyEnabled"]?.toBooleanStrictOrNull() ?: true
                    val fileName = call.request.queryParameters["fileName"] ?: "upload.zip"

                    if (name.isBlank() || !name.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                        return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid name", ApiError.VALIDATION_FAILED))
                    }
                    if (dedicatedServiceManager.getConfig(name) != null) {
                        return@post call.respond(HttpStatusCode.Conflict, apiError("Dedicated service '$name' already exists", ApiError.DEDICATED_ALREADY_EXISTS))
                    }
                    if (port < 1 || port > 65535) {
                        return@post call.respond(HttpStatusCode.BadRequest, apiError("Port must be between 1 and 65535", ApiError.VALIDATION_FAILED))
                    }

                    val uploadDir = templatesDir.resolve(".modpack-uploads")
                    Files.createDirectories(uploadDir)
                    val uploadedZip = uploadDir.resolve(fileName)

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

                    handleDedicatedModpackImport(
                        call, installer, softwareResolver, dedicatedServiceManager, eventBus,
                        uploadedZip, name, port, memory, proxyEnabled
                    )
                }

                // POST /api/dedicated/modpack/upload/finalize?uploadId=X&name=...&port=...&memory=...&proxyEnabled=...
                // Reuses the in-memory chunked-upload state from /api/modpacks/upload/{init,chunk}.
                // The init call must have been made with `target=dedicated`.
                post("upload/finalize") {
                    if (!call.requirePermission("nimbus.dashboard.dedicated.manage")) return@post
                    val uploadId = call.request.queryParameters["uploadId"] ?: ""
                    val name = call.request.queryParameters["name"] ?: ""
                    val port = call.request.queryParameters["port"]?.toIntOrNull() ?: 0
                    val memory = call.request.queryParameters["memory"] ?: "4G"
                    val proxyEnabled = call.request.queryParameters["proxyEnabled"]?.toBooleanStrictOrNull() ?: true

                    val state = chunkedUploads.remove(uploadId)
                        ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Upload not found or expired", ApiError.CHUNKED_UPLOAD_NOT_FOUND))

                    if (state.target != "dedicated") {
                        Files.deleteIfExists(state.filePath)
                        return@post call.respond(HttpStatusCode.BadRequest,
                            apiError("Upload was initialised with target='${state.target}'. Use /api/modpacks/upload/finalize instead.", ApiError.VALIDATION_FAILED))
                    }
                    if (state.receivedChunks.size != state.totalChunks) {
                        Files.deleteIfExists(state.filePath)
                        return@post call.respond(HttpStatusCode.BadRequest,
                            apiError("Upload incomplete: ${state.receivedChunks.size}/${state.totalChunks} chunks received", ApiError.CHUNKED_UPLOAD_INVALID))
                    }
                    if (name.isBlank() || !VALID_NAME.matches(name)) {
                        Files.deleteIfExists(state.filePath)
                        return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid name", ApiError.VALIDATION_FAILED))
                    }
                    if (dedicatedServiceManager.getConfig(name) != null) {
                        Files.deleteIfExists(state.filePath)
                        return@post call.respond(HttpStatusCode.Conflict, apiError("Dedicated service '$name' already exists", ApiError.DEDICATED_ALREADY_EXISTS))
                    }
                    if (port < 1 || port > 65535) {
                        Files.deleteIfExists(state.filePath)
                        return@post call.respond(HttpStatusCode.BadRequest, apiError("Port must be between 1 and 65535", ApiError.VALIDATION_FAILED))
                    }

                    handleDedicatedModpackImport(
                        call, installer, softwareResolver, dedicatedServiceManager, eventBus,
                        state.filePath, name, port, memory, proxyEnabled
                    )
                }
            }
        }
    }
}

/**
 * Shared import logic for dedicated services from a server pack ZIP or .mrpack.
 * Called by both the single-shot upload endpoint and the chunked-upload finalize endpoint.
 */
private suspend fun handleDedicatedModpackImport(
    call: io.ktor.server.routing.RoutingCall,
    installer: ModpackInstaller,
    softwareResolver: SoftwareResolver,
    dedicatedServiceManager: DedicatedServiceManager,
    eventBus: EventBus,
    uploadedZip: Path,
    name: String,
    port: Int,
    memory: String,
    proxyEnabled: Boolean,
) {
    val serviceDir = dedicatedServiceManager.ensureServiceDirectory(name)

    if (installer.isServerPack(uploadedZip)) {
        val info = installer.getServerPackInfo(uploadedZip)
        if (info == null) {
            Files.deleteIfExists(uploadedZip)
            return call.respond(HttpStatusCode.BadRequest, apiError("Could not analyze server pack", ApiError.MODPACK_INVALID))
        }
        installer.extractServerPack(uploadedZip, serviceDir)
        softwareResolver.ensureJarAvailable(info.modloader, info.mcVersion, serviceDir, info.modloaderVersion)
        when (info.modloader) {
            ServerSoftware.FABRIC -> softwareResolver.ensureFabricProxyMod(serviceDir, info.mcVersion)
            ServerSoftware.FORGE, ServerSoftware.NEOFORGE -> softwareResolver.ensureForwardingMod(info.modloader, info.mcVersion, serviceDir)
            else -> {}
        }
        serviceDir.resolve("eula.txt").toFile().writeText("eula=true\n")

        val config = DedicatedServiceConfig(
            dedicated = DedicatedDefinition(
                name = name, port = port,
                software = info.modloader, version = info.mcVersion,
                jarName = "", readyPattern = "", javaPath = "",
                proxyEnabled = proxyEnabled, memory = memory,
                restartOnCrash = true, maxRestarts = 5, jvm = JvmConfig()
            )
        )
        dedicatedServiceManager.writeTOML(config)
        dedicatedServiceManager.addConfig(config)
        eventBus.emit(NimbusEvent.DedicatedCreated(name))

        Files.deleteIfExists(uploadedZip)
        return call.respond(HttpStatusCode.Created, ModpackImportResponse(
            success = true,
            message = "Server pack uploaded and imported as dedicated service '$name' (${info.modloader.name} ${info.modloaderVersion}, MC ${info.mcVersion})",
            groupName = name,
            filesDownloaded = info.serverFiles,
            filesFailed = 0
        ))
    }

    // .mrpack or unrecognized
    val index = installer.parseIndex(uploadedZip)
    if (index == null) {
        Files.deleteIfExists(uploadedZip)
        return call.respond(HttpStatusCode.BadRequest, apiError("Uploaded file is not a valid server pack ZIP or .mrpack", ApiError.MODPACK_INVALID))
    }
    val info = installer.getInfo(index)
    softwareResolver.ensureJarAvailable(info.modloader, info.mcVersion, serviceDir, info.modloaderVersion)
    val result = installer.installFiles(index, serviceDir) { _, _, _ -> }
    installer.extractOverrides(uploadedZip, serviceDir)
    when (info.modloader) {
        ServerSoftware.FABRIC -> softwareResolver.ensureFabricProxyMod(serviceDir, info.mcVersion)
        ServerSoftware.FORGE, ServerSoftware.NEOFORGE -> softwareResolver.ensureForwardingMod(info.modloader, info.mcVersion, serviceDir)
        else -> {}
    }
    serviceDir.resolve("eula.txt").toFile().writeText("eula=true\n")

    val config = DedicatedServiceConfig(
        dedicated = DedicatedDefinition(
            name = name, port = port,
            software = info.modloader, version = info.mcVersion,
            jarName = "", readyPattern = "", javaPath = "",
            proxyEnabled = proxyEnabled, memory = memory,
            restartOnCrash = true, maxRestarts = 5, jvm = JvmConfig()
        )
    )
    dedicatedServiceManager.writeTOML(config)
    dedicatedServiceManager.addConfig(config)
    eventBus.emit(NimbusEvent.DedicatedCreated(name))

    Files.deleteIfExists(uploadedZip)
    call.respond(HttpStatusCode.Created, ModpackImportResponse(
        success = result.success,
        message = if (result.success) "Modpack '${info.name}' uploaded and imported as dedicated service '$name'"
                 else "Import completed with ${result.filesFailed} failed downloads",
        groupName = name,
        filesDownloaded = result.filesDownloaded,
        filesFailed = result.filesFailed
    ))
}

@kotlinx.serialization.Serializable
data class DedicatedModpackImportRequest(
    val source: String,
    val name: String,
    val port: Int,
    val memory: String = "4G",
    val proxyEnabled: Boolean = true
)

private fun validateDedicatedRequest(request: CreateDedicatedRequest): List<String> {
    val errors = mutableListOf<String>()

    if (!VALID_NAME.matches(request.name)) {
        errors += "Invalid name '${request.name}' — only alphanumeric, dash and underscore allowed (max 64 chars)"
    }

    if (request.port < 1 || request.port > 65535) {
        errors += "Port must be between 1 and 65535"
    }

    // Validate software enum
    try { ServerSoftware.valueOf(request.software.uppercase()) }
    catch (_: IllegalArgumentException) { errors += "Invalid software '${request.software}'. Valid: ${ServerSoftware.entries.joinToString()}" }

    // Validate memory format
    if (!request.memory.matches(Regex("^\\d+[MmGg]$"))) {
        errors += "Invalid memory format '${request.memory}' — expected e.g. '512M' or '2G'"
    }

    // Validate version format
    if (!request.version.matches(Regex("^\\d+\\.\\d+(\\.\\d+)?(-.*)?$"))) {
        errors += "Invalid version '${request.version}' — expected e.g. '1.21.4'"
    }

    if (request.maxRestarts < 0) errors += "max_restarts must be >= 0"

    return errors
}

private fun DedicatedServiceConfig.toResponse(
    registry: ServiceRegistry,
    dedicatedServiceManager: DedicatedServiceManager
): DedicatedServiceResponse {
    val def = dedicated
    val service = registry.get(def.name)

    val uptime = if (service?.startedAt != null) {
        val duration = Duration.between(service.startedAt, Instant.now())
        val hours = duration.toHours()
        val minutes = duration.toMinutesPart()
        val seconds = duration.toSecondsPart()
        "${hours}h ${minutes}m ${seconds}s"
    } else null

    return DedicatedServiceResponse(
        name = def.name,
        directory = dedicatedServiceManager.getServiceDirectory(def.name).toString(),
        port = def.port,
        software = def.software.name,
        version = def.version,
        memory = def.memory,
        proxyEnabled = def.proxyEnabled,
        restartOnCrash = def.restartOnCrash,
        maxRestarts = def.maxRestarts,
        jvmArgs = def.jvm.args,
        jvmOptimize = def.jvm.optimize,
        state = service?.state?.name,
        pid = service?.pid,
        playerCount = service?.playerCount,
        uptime = uptime
    )
}
