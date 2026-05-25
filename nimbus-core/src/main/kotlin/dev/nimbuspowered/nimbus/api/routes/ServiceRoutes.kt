package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.*
import dev.nimbuspowered.nimbus.api.ApiError
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.database.DatabaseManager
import dev.nimbuspowered.nimbus.database.ServiceMetricSamples
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.service.DedicatedServiceManager
import dev.nimbuspowered.nimbus.service.ServiceMemoryResolver
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceState
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import java.io.RandomAccessFile
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

@Serializable
data class MigrateRequest(val target: String? = null)

fun Route.serviceRoutes(
    registry: ServiceRegistry,
    serviceManager: ServiceManager,
    groupManager: GroupManager,
    eventBus: EventBus,
    databaseManager: DatabaseManager? = null,
    stateSyncManager: dev.nimbuspowered.nimbus.service.StateSyncManager? = null
) {
    val dedicatedServiceManager: DedicatedServiceManager? = serviceManager.dedicatedServiceManager
    route("/api/services") {

        // GET /api/services — List all services
        get {
            if (!call.requirePermission("nimbus.dashboard.services.view")) return@get
            val group = call.queryParameters["group"]
            val stateParam = call.queryParameters["state"]

            var services = if (group != null) {
                registry.getByGroup(group)
            } else {
                registry.getAll()
            }

            if (stateParam != null) {
                val stateFilter = try {
                    ServiceState.valueOf(stateParam.uppercase())
                } catch (_: IllegalArgumentException) {
                    return@get call.respond(
                        HttpStatusCode.BadRequest,
                        apiError("Invalid state: '$stateParam'. Valid: ${ServiceState.entries.joinToString()}", ApiError.VALIDATION_FAILED)
                    )
                }
                services = services.filter { it.state == stateFilter }
            }

            val customStateParam = call.queryParameters["customState"]
            if (customStateParam != null) {
                services = services.filter { it.customState.equals(customStateParam, ignoreCase = true) }
            }

            val responses = services.map { it.toResponse(groupManager, dedicatedServiceManager, stateSyncManager, serviceManager) }
            call.respond(ServiceListResponse(responses, responses.size))
        }

        // GET /api/services/health — Aggregated health summary
        get("health") {
            if (!call.requirePermission("nimbus.dashboard.services.view")) return@get
            val allServices = registry.getAll()
            val readyServices = allServices.filter { it.state == ServiceState.READY }
            val healthyCount = readyServices.count { it.healthy }
            val unhealthyCount = readyServices.size - healthyCount

            val avgTps = if (readyServices.isNotEmpty()) {
                readyServices.sumOf { it.tps } / readyServices.size
            } else 0.0

            // Resolve real memory for each service (reads /proc, falls back to config)
            val memPerService = allServices.associateWith {
                ServiceMemoryResolver.resolve(it, groupManager, dedicatedServiceManager)
            }
            val totalUsedMb = memPerService.values.sumOf { it.usedMb }
            val totalMaxMb = memPerService.values.sumOf { it.maxMb }
            val memPct = if (totalMaxMb > 0) totalUsedMb.toDouble() / totalMaxMb * 100 else 0.0

            val entries = allServices.sortedBy { it.name }.map { svc ->
                val uptimeSec = svc.startedAt?.let { Duration.between(it, Instant.now()).seconds }
                val (used, max) = memPerService[svc]?.let { it.usedMb to it.maxMb } ?: (0L to 0L)
                ServiceHealthEntry(
                    name = svc.name,
                    groupName = svc.groupName,
                    state = svc.state.name,
                    tps = svc.tps,
                    memoryUsedMb = used,
                    memoryMaxMb = max,
                    healthy = svc.healthy,
                    restartCount = svc.restartCount,
                    uptimeSeconds = uptimeSec
                )
            }

            call.respond(ServiceHealthSummaryResponse(
                totalServices = allServices.size,
                readyServices = readyServices.size,
                healthyServices = healthyCount,
                unhealthyServices = unhealthyCount,
                averageTps = Math.round(avgTps * 100.0) / 100.0,
                totalMemoryUsedMb = totalUsedMb,
                totalMemoryMaxMb = totalMaxMb,
                memoryUsagePercent = Math.round(memPct * 100.0) / 100.0,
                services = entries
            ))
        }

        // GET /api/services/{name} — Get service details
        get("{name}") {
            if (!call.requirePermission("nimbus.dashboard.services.view")) return@get
            val name = call.parameters["name"]!!
            val service = registry.get(name)
                ?: return@get call.respond(HttpStatusCode.NotFound, apiError("Service '$name' not found", ApiError.SERVICE_NOT_FOUND))
            call.respond(service.toResponse(groupManager, dedicatedServiceManager, stateSyncManager, serviceManager))
        }

        // GET /api/services/{name}/metrics/history — Historical memory + player samples.
        // Reads from the `service_metric_samples` table written by MetricsCollector.
        // Used by the dashboard chart so users see memory over the last hour instead
        // of starting from blank when they open the page.
        get("{name}/metrics/history") {
            if (!call.requirePermission("nimbus.dashboard.services.view")) return@get
            val name = call.parameters["name"]!!
            if (registry.get(name) == null) {
                return@get call.respond(
                    HttpStatusCode.NotFound,
                    apiError("Service '$name' not found", ApiError.SERVICE_NOT_FOUND)
                )
            }
            val db = databaseManager
            if (db == null) {
                return@get call.respond(ServiceMetricHistoryResponse(name, emptyList()))
            }
            val minutes = (call.queryParameters["minutes"]?.toIntOrNull() ?: 60).coerceIn(5, 24 * 60)
            val since = Instant.now().minus(minutes.toLong(), ChronoUnit.MINUTES).toString()

            val samples = db.query {
                ServiceMetricSamples.selectAll()
                    .where { (ServiceMetricSamples.serviceName eq name) and (ServiceMetricSamples.timestamp greaterEq since) }
                    .orderBy(ServiceMetricSamples.timestamp, SortOrder.ASC)
                    .limit(2000)
                    .map { row ->
                        ServiceMetricSampleResponse(
                            timestamp = row[ServiceMetricSamples.timestamp],
                            memoryUsedMb = row[ServiceMetricSamples.memoryUsedMb],
                            memoryMaxMb = row[ServiceMetricSamples.memoryMaxMb],
                            playerCount = row[ServiceMetricSamples.playerCount],
                        )
                    }
            }
            call.respond(ServiceMetricHistoryResponse(name, samples))
        }

        // POST /api/services/{name}/start — Start a new instance of a group
        post("{name}/start") {
            if (!call.requirePermission("nimbus.dashboard.services.start")) return@post
            val groupName = call.parameters["name"]!!

            if (groupManager.getGroup(groupName) == null) {
                return@post call.respond(HttpStatusCode.NotFound, apiError("Group '$groupName' not found", ApiError.GROUP_NOT_FOUND))
            }

            val service = serviceManager.startService(groupName)
            if (service != null) {
                call.respond(HttpStatusCode.Created, ApiMessage(true, "Service '${service.name}' starting on port ${service.port}"))
            } else {
                call.respond(HttpStatusCode.Conflict, apiError("Failed to start service for group '$groupName' — max instances reached or JAR unavailable", ApiError.SERVICE_START_FAILED))
            }
        }

        // POST /api/services/{name}/stop — Stop a service
        post("{name}/stop") {
            if (!call.requirePermission("nimbus.dashboard.services.stop")) return@post
            val name = call.parameters["name"]!!
            registry.get(name)
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Service '$name' not found", ApiError.SERVICE_NOT_FOUND))

            val stopped = serviceManager.stopService(name)
            if (stopped) {
                call.respond(ApiMessage(true, "Service '$name' stopped"))
            } else {
                call.respond(HttpStatusCode.InternalServerError, apiError("Failed to stop service '$name'", ApiError.SERVICE_STOP_FAILED))
            }
        }

        // POST /api/services/{name}/restart — Restart a service
        post("{name}/restart") {
            if (!call.requirePermission("nimbus.dashboard.services.restart")) return@post
            val name = call.parameters["name"]!!
            registry.get(name)
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Service '$name' not found", ApiError.SERVICE_NOT_FOUND))

            val newService = serviceManager.restartService(name)
            if (newService != null) {
                call.respond(ApiMessage(true, "Service restarted as '${newService.name}' on port ${newService.port}"))
            } else {
                call.respond(HttpStatusCode.InternalServerError, apiError("Failed to restart service '$name'", ApiError.SERVICE_RESTART_FAILED))
            }
        }

        // POST /api/services/{name}/migrate — Move a service to a different node
        post("{name}/migrate") {
            if (!call.requirePermission("nimbus.dashboard.nodes.manage")) return@post
            val name = call.parameters["name"]!!
            registry.get(name)
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Service '$name' not found", ApiError.SERVICE_NOT_FOUND))

            val body = try {
                call.receive<MigrateRequest>()
            } catch (_: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid request body — expected { \"target\": \"<node>\" }", ApiError.VALIDATION_FAILED))
            }
            val target = body.target?.takeIf { it.isNotBlank() }

            val migrated = serviceManager.migrateService(name, target)
            if (migrated != null) {
                call.respond(ApiMessage(true, "Service '$name' migrated to node '${migrated.nodeId}'"))
            } else {
                call.respond(HttpStatusCode.InternalServerError, apiError("Migration failed for service '$name'", ApiError.SERVICE_RESTART_FAILED))
            }
        }

        // POST /api/services/{name}/exec — Execute command on service
        post("{name}/exec") {
            if (!call.requirePermission("nimbus.dashboard.services.console")) return@post
            val name = call.parameters["name"]!!
            registry.get(name)
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Service '$name' not found", ApiError.SERVICE_NOT_FOUND))

            val request = call.receive<ExecRequest>()
            val success = serviceManager.executeCommand(name, request.command)
            call.respond(ExecResponse(success, name, request.command))
        }

        // PUT /api/services/{name}/state — Set custom state (used by plugins via SDK)
        put("{name}/state") {
            val name = call.parameters["name"]!!
            val service = registry.get(name)
                ?: return@put call.respond(HttpStatusCode.NotFound, apiError("Service '$name' not found", ApiError.SERVICE_NOT_FOUND))

            if (service.state != ServiceState.READY) {
                return@put call.respond(HttpStatusCode.Conflict, apiError("Service '$name' is not READY (current: ${service.state})", ApiError.SERVICE_NOT_READY))
            }

            val request = call.receive<SetCustomStateRequest>()
            val oldState = service.customState
            service.customState = request.customState

            eventBus.emit(
                NimbusEvent.ServiceCustomStateChanged(
                    serviceName = name,
                    groupName = service.groupName,
                    oldState = oldState,
                    newState = request.customState
                )
            )

            call.respond(CustomStateResponse(name, service.customState))
        }

        // GET /api/services/{name}/state — Get custom state
        get("{name}/state") {
            val name = call.parameters["name"]!!
            val service = registry.get(name)
                ?: return@get call.respond(HttpStatusCode.NotFound, apiError("Service '$name' not found", ApiError.SERVICE_NOT_FOUND))

            call.respond(CustomStateResponse(name, service.customState))
        }

        // PUT /api/services/{name}/health — Report TPS (used by SDK on backend servers).
        // Memory fields in the request are accepted but ignored — the controller now
        // reads resident process memory from /proc for accuracy and consistency.
        put("{name}/health") {
            val name = call.parameters["name"]!!
            val service = registry.get(name)
                ?: return@put call.respond(HttpStatusCode.NotFound, apiError("Service '$name' not found", ApiError.SERVICE_NOT_FOUND))

            val request = call.receive<ReportHealthRequest>()
            service.updateTps(request.tps)

            call.respond(HealthReportResponse(name, service.healthy))
        }

        // PUT /api/services/{name}/players — Report player count (used by SDK on backend servers)
        put("{name}/players") {
            val name = call.parameters["name"]!!
            val service = registry.get(name)
                ?: return@put call.respond(HttpStatusCode.NotFound, apiError("Service '$name' not found", ApiError.SERVICE_NOT_FOUND))

            val request = call.receive<ReportPlayerCountRequest>()
            service.playerCount = request.playerCount
            service.lastPlayerCountUpdate = java.time.Instant.now()

            call.respond(PlayerCountResponse(name, service.playerCount))
        }

        // GET /api/services/{name}/logs?source={app|stdout} — Get recent log lines (tail-read).
        // - source=app (default): the server software's own log (e.g. logs/latest.log
        //   for Paper/NeoForge). Best for in-game/server activity.
        // - source=stdout: raw process stdout/stderr captured by Nimbus. Useful for
        //   crashes where the server died before flushing its app log (e.g. an
        //   unhandled JVM exception during mod loading).
        get("{name}/logs") {
            if (!call.requirePermission("nimbus.dashboard.services.console")) return@get
            val name = call.parameters["name"]!!
            val service = registry.get(name)
                ?: return@get call.respond(HttpStatusCode.NotFound, apiError("Service '$name' not found", ApiError.SERVICE_NOT_FOUND))

            val source = call.queryParameters["source"]?.lowercase() ?: "app"
            val logPath = when (source) {
                "stdout", "raw" -> service.workingDirectory.resolve(dev.nimbuspowered.nimbus.service.ProcessHandle.STDOUT_FILE_NAME)
                else -> service.workingDirectory.resolve("logs/latest.log")
            }
            val logFile = logPath.toFile()
            if (!logFile.exists()) {
                return@get call.respond(LogsResponse(name, emptyList(), 0))
            }

            val requestedLines = (call.queryParameters["lines"]?.toIntOrNull() ?: 100).coerceIn(1, 1000)
            val logLines = tailFile(logFile, requestedLines)
            call.respond(LogsResponse(name, logLines, logLines.size))
        }

        // POST /api/services/{name}/message — Send a message to a service (service-to-service messaging)
        post("{name}/message") {
            val targetName = call.parameters["name"]!!

            // "controller" is a virtual target — messages are emitted directly to the EventBus
            // without requiring a registered service entry (the controller itself isn't a service)
            if (targetName != "controller") {
                registry.get(targetName)
                    ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Service '$targetName' not found", ApiError.SERVICE_NOT_FOUND))
            }

            val request = call.receive<SendMessageRequest>()

            eventBus.emit(
                NimbusEvent.ServiceMessage(
                    fromService = request.from,
                    toService = targetName,
                    channel = request.channel,
                    data = request.data
                )
            )

            call.respond(ApiMessage(true, "Message sent to '$targetName' on channel '${request.channel}'"))
        }
    }
}

/**
 * Efficiently reads the last N lines from a file using reverse seeking.
 */
private fun tailFile(file: java.io.File, lines: Int): List<String> {
    if (file.length() == 0L) return emptyList()

    RandomAccessFile(file, "r").use { raf ->
        val fileLength = raf.length()
        var pos = fileLength - 1
        var lineCount = 0

        // Scan backwards to find enough newlines
        while (pos > 0 && lineCount <= lines) {
            raf.seek(pos)
            if (raf.readByte().toInt().toChar() == '\n') {
                lineCount++
            }
            pos--
        }

        // Position to start reading
        if (pos == 0L) {
            raf.seek(0)
        } else {
            raf.seek(pos + 2) // Skip past the newline we stopped on
        }

        val result = mutableListOf<String>()
        var line = raf.readLine()
        while (line != null) {
            result.add(line)
            line = raf.readLine()
        }
        return result.takeLast(lines)
    }
}

private fun dev.nimbuspowered.nimbus.service.Service.toResponse(
    groupManager: GroupManager,
    dedicatedServiceManager: DedicatedServiceManager?,
    stateSyncManager: dev.nimbuspowered.nimbus.service.StateSyncManager? = null,
    serviceManager: dev.nimbuspowered.nimbus.service.ServiceManager? = null
): ServiceResponse {
    val uptime = if (startedAt != null) {
        val duration = Duration.between(startedAt, Instant.now())
        val hours = duration.toHours()
        val minutes = duration.toMinutesPart()
        val seconds = duration.toSecondsPart()
        "${hours}h ${minutes}m ${seconds}s"
    } else null

    val mem = ServiceMemoryResolver.resolve(this, groupManager, dedicatedServiceManager)

    // Sync health: only populated if state sync is enabled for this service
    // (group has [group.sync] enabled or dedicated has [dedicated.sync] enabled).
    // We determine this by checking if the controller has any canonical for the service.
    val syncHealth = stateSyncManager?.let { ssm ->
        val canonicalSize = ssm.canonicalSizeBytes(name)
        val stats = ssm.getStats(name)
        val inFlight = ssm.isSyncInFlight(name)
        if (canonicalSize > 0 || stats != null || inFlight) {
            SyncHealth(
                inFlight = inFlight,
                lastPushAt = stats?.lastPushAtEpochMs?.let { Instant.ofEpochMilli(it).toString() },
                lastPushBytes = stats?.lastPushBytes ?: 0,
                lastPushFiles = stats?.lastPushFiles ?: 0,
                canonicalSizeBytes = canonicalSize
            )
        } else null
    }

    return ServiceResponse(
        name = name,
        groupName = groupName,
        port = port,
        host = host,
        nodeId = nodeId,
        state = state.name,
        customState = customState,
        pid = pid,
        playerCount = playerCount,
        startedAt = startedAt?.toString(),
        restartCount = restartCount,
        uptime = uptime,
        isStatic = isStatic,
        isDedicated = isDedicated,
        proxyEnabled = proxyEnabled,
        bedrockPort = bedrockPort,
        tps = tps,
        memoryUsedMb = mem.usedMb,
        memoryMaxMb = mem.maxMb,
        healthy = healthy,
        sync = syncHealth,
        backedBy = serviceManager?.getProcessHandle(name)?.kind ?: "process"
    )
}
