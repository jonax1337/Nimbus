package dev.nimbuspowered.nimbus.service

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.time.Duration

class ProcessHandle : ServiceHandle {

    private val logger = LoggerFactory.getLogger(ProcessHandle::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var process: Process? = null
    private var adoptedHandle: java.lang.ProcessHandle? = null
    private var stdinWriter: BufferedWriter? = null

    private val _stdoutLines = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val stdoutLines: SharedFlow<String> = _stdoutLines.asSharedFlow()

    /** Rolling tail buffer of the last [TAIL_CAPACITY] stdout lines, for crash diagnostics. */
    private val tailBuffer = ArrayDeque<String>(TAIL_CAPACITY)
    private val tailLock = Any()

    private var donePattern = Regex("""Done \(""")

    fun setReadyPattern(pattern: Regex) {
        donePattern = pattern
    }

    fun start(workDir: Path, command: List<String>, env: Map<String, String> = emptyMap()) {
        logger.info("Starting process in {} with command: {}", workDir, command)
        val pb = ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
        if (env.isNotEmpty()) {
            pb.environment().putAll(env)
        }
        process = pb.start()
        stdinWriter = BufferedWriter(OutputStreamWriter(process!!.outputStream))

        // Persist raw stdout/stderr (with redirectErrorStream=true these are
        // already merged) so JVM-level exceptions written directly to stderr
        // — like Forge/NeoForge mod-loading errors that bypass log4j's
        // asynclogger and never reach logs/latest.log — can be inspected
        // post-mortem. Without this, crashing services leave no on-disk
        // trace of the actual failure.
        val rawLogPath = workDir.resolve(STDOUT_FILE_NAME)
        val rawLogWriter = try {
            runCatching { Files.createDirectories(workDir) }
            Files.newBufferedWriter(
                rawLogPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        } catch (e: Exception) {
            logger.warn("Could not open {} for raw stdout capture: {}", rawLogPath, e.message)
            null
        }

        scope.launch {
            try {
                process!!.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        synchronized(tailLock) {
                            if (tailBuffer.size >= TAIL_CAPACITY) tailBuffer.removeFirst()
                            tailBuffer.addLast(line)
                        }
                        rawLogWriter?.let { writer ->
                            try {
                                writer.write(line)
                                writer.newLine()
                                writer.flush()
                            } catch (_: Exception) {
                                // Disk full / closed — keep streaming in-memory.
                            }
                        }
                        _stdoutLines.emit(line)
                    }
                }
            } catch (e: Exception) {
                logger.warn("stdout reader terminated: {}", e.message)
            } finally {
                rawLogWriter?.runCatching { close() }
            }
        }
    }

    override suspend fun sendCommand(command: String) {
        withContext(Dispatchers.IO) {
            stdinWriter?.let { writer ->
                writer.write(command)
                writer.newLine()
                writer.flush()
                logger.debug("Sent command: {}", command)
            } ?: logger.warn("Cannot send command, process stdin is not available")
        }
    }

    override suspend fun waitForReady(timeout: Duration): Boolean {
        return try {
            withTimeout(timeout.inWholeMilliseconds) {
                stdoutLines.first { line -> donePattern.containsMatchIn(line) }
                true
            }
        } catch (_: TimeoutCancellationException) {
            logger.warn("Timed out waiting for server ready after {}", timeout)
            false
        }
    }

    override suspend fun stopGracefully(timeout: Duration) {
        logger.info("Initiating graceful stop with timeout {}", timeout)
        try {
            sendCommand("stop")
            val exited = withContext(Dispatchers.IO) {
                process?.waitFor(timeout.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
                    ?: true
            }
            if (!exited) {
                logger.warn("Process did not stop within timeout, force killing")
                process?.destroyForcibly()
            }
        } catch (e: Exception) {
            logger.error("Error during graceful stop, force killing", e)
            process?.destroyForcibly()
        }
    }

    override fun isAlive(): Boolean = process?.isAlive ?: adoptedHandle?.isAlive ?: false

    override fun pid(): Long? = try {
        process?.pid() ?: adoptedHandle?.pid()
    } catch (_: UnsupportedOperationException) {
        null
    }

    override fun exitCode(): Int? = try {
        process?.exitValue()
    } catch (_: IllegalThreadStateException) {
        null
    }

    override suspend fun awaitExit(): Int? {
        return withContext(Dispatchers.IO) {
            if (process != null) {
                process?.onExit()?.await()
            } else if (adoptedHandle != null) {
                adoptedHandle?.onExit()?.await()
            }
            exitCode()
        }
    }

    override fun snapshotTail(): List<String> = synchronized(tailLock) { tailBuffer.toList() }

    override fun destroy() {
        logger.info("Destroying process handle")
        scope.cancel()
        stdinWriter?.runCatching { close() }
        process?.destroyForcibly()
        adoptedHandle?.destroyForcibly()
    }

    companion object {
        private const val TAIL_CAPACITY = 50
        /** File name (inside each service's workDir) that captures raw process stdout/stderr. */
        const val STDOUT_FILE_NAME = "nimbus-stdout.log"
        private val adoptLogger = LoggerFactory.getLogger(ProcessHandle::class.java)

        /**
         * Attempts to adopt an existing OS process by PID.
         *
         * **Windows limitation:** [java.lang.ProcessHandle.of] may return empty for
         * grandchild processes (e.g. when a wrapper script spawns Java). This is a
         * JVM limitation on Windows where the process tree is not fully visible.
         * In such cases, adoption will silently fail and the service will be treated
         * as not running.
         */
        fun adopt(pid: Long, serviceName: String): ProcessHandle? {
            val osHandle = java.lang.ProcessHandle.of(pid).orElse(null)
            if (osHandle == null) {
                adoptLogger.debug("PID {} does not exist (may be a grandchild process on Windows)", pid)
                return null
            }
            if (!osHandle.isAlive) {
                adoptLogger.debug("PID {} is not alive", pid)
                return null
            }

            // On Windows, ProcessHandle.info().commandLine() often returns empty
            val cmdLine = osHandle.info().commandLine().orElse("")
            if (cmdLine.isNotEmpty()) {
                if (!cmdLine.contains("nimbus.service.name=$serviceName")) {
                    adoptLogger.warn("PID {} exists but is not service '{}' — skipping adoption", pid, serviceName)
                    return null
                }
            } else {
                val command = osHandle.info().command().orElse("")
                if (command.isNotEmpty() && !command.contains("java")) {
                    adoptLogger.warn("PID {} exists but is not a Java process ('{}') — skipping adoption", pid, command)
                    return null
                }
                adoptLogger.info("PID {} command line not available (Windows) — adopting based on PID match", pid)
            }

            val handle = ProcessHandle()
            handle.adoptedHandle = osHandle
            adoptLogger.info("Adopted process PID {} for service '{}'", pid, serviceName)
            return handle
        }
    }
}
