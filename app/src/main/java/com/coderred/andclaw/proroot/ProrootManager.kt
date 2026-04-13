package com.coderred.andclaw.proroot

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * proroot 바이너리, rootfs, Node.js 경로를 관리하고
 * proroot 명령어를 구성하는 핵심 매니저.
 *
 * proroot 바이너리는 APK의 jniLibs/arm64-v8a/libproroot.so 로 패키징되어
 * 설치 시 nativeLibraryDir에 자동 추출된다.
 */
class ProrootManager(private val context: Context) {
    data class CommandResult(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean = false,
    )

    companion object {
        // Ubuntu 24.04 LTS arm64 base rootfs (bundled in assets)
        const val ROOTFS_ASSET = "rootfs.tar.gz.bin"

        // Node.js 24 LTS arm64 linux (bundled in assets)
        const val NODEJS_VERSION = "v24.2.0"
        const val NODEJS_ASSET = "node-arm64.tar.gz.bin"
        const val NODEJS_DIR_NAME = "node-$NODEJS_VERSION-linux-arm64"

        // 3분할 번들 에셋
        const val SYSTEM_TOOLS_ASSET = "system-tools-arm64.tar.gz.bin"
        const val OPENCLAW_ASSET = "openclaw-arm64.tar.gz.bin"
        const val PLAYWRIGHT_ASSET = "playwright-chromium-arm64.tar.gz.bin"
        private const val PLAYWRIGHT_CHROME_MARKER = ".playwright_chrome_path"
        const val OPENCLAW_NODE_BIN = "/usr/local/bin/node"
        const val OPENCLAW_ENTRYPOINT = "/usr/local/lib/node_modules/openclaw/openclaw.mjs"
        const val GUEST_HOOK_LIB_PATH = "/root/.proroot/libproroot-runtime.so"

        const val GUEST_VFORK_SHIM_PATH = "/root/.proroot/libvfork_shim.so"
    }

    // ── 경로 ──

    /** nativeLibraryDir 의 proroot direct-exec binary (APK에서 추출됨) */
    private val nativeProrootPath: String
        get() = File(context.applicationInfo.nativeLibraryDir, "libproroot.so").absolutePath

    /** nativeLibraryDir 의 proroot hook library */
    private val nativeHookLibPath: String
        get() = File(context.applicationInfo.nativeLibraryDir, "libproroot-runtime.so").absolutePath


    /**
     * rootfs linker가 접근 가능한 경로에 복사된 hook library.
     * nativeLibraryDir는 SELinux로 rootfs linker가 접근 불가하므로
     * filesDir로 복사해서 LD_PRELOAD에 사용한다.
     */
    val hookLibPath: File
        get() = File(context.filesDir, "libproroot-runtime.so")

    val guestHookLibHostPath: File
        get() = File(rootfsDir, "root/.proroot/libproroot-runtime.so")


    val guestVforkShimHostPath: File
        get() = File(rootfsDir, "root/.proroot/libvfork_shim.so")

    /** 실제 실행할 proroot 바이너리 경로 (nativeLibraryDir 에서 직접 실행) */
    val prorootBinaryPath: String
        get() = nativeProrootPath

    val rootfsDir: File
        get() = File(context.filesDir, "rootfs")

    val cacheDir: File
        get() = context.cacheDir

    private val playwrightCacheDir: File
        get() = File(rootfsDir, "root/.cache/ms-playwright")

    private val playwrightChromeMarkerFile: File
        get() = File(rootfsDir, PLAYWRIGHT_CHROME_MARKER)

    val homeDir: File
        get() = File(rootfsDir, "root")

    // ── 상태 확인 ──

    val isProrootAvailable: Boolean
        get() {
            val native = File(nativeProrootPath)
            return native.exists() && native.canExecute()
        }

    val isRootfsInstalled: Boolean
        get() = File(rootfsDir, "bin/sh").exists()

    val isNodeInstalled: Boolean
        get() = File(rootfsDir, "usr/local/bin/node").exists()

    val isSystemToolsInstalled: Boolean
        get() = File(rootfsDir, "usr/bin/git").exists()

    val isOpenClawInstalled: Boolean
        get() = File(rootfsDir, "usr/local/lib/node_modules/openclaw").exists() &&
            File(rootfsDir, "usr/local/bin/openclaw").exists()

    val isChromiumInstalled: Boolean
        get() {
            val markerPath = readChromiumMarkerPath()
            if (markerPath != null) {
                val markerBinary = File(markerPath)
                if (markerBinary.exists() && markerBinary.canExecute()) {
                    return true
                }
            }

            val detectedPath = detectChromiumExecutablePath()
            if (detectedPath != null) {
                writeChromiumMarkerPath(detectedPath)
                return true
            }

            clearChromiumMarkerPath()
            return false
        }

    val isFullySetup: Boolean
        get() = isProrootAvailable && isRootfsInstalled && isNodeInstalled && isOpenClawInstalled

    fun refreshChromiumExecutableMarker(): Boolean {
        val detectedPath = detectChromiumExecutablePath() ?: run {
            clearChromiumMarkerPath()
            return false
        }

        writeChromiumMarkerPath(detectedPath)
        return true
    }

    fun detectChromiumExecutableProotPath(): String? {
        val detectedPath = detectChromiumExecutablePath() ?: return null
        return "/" + File(detectedPath).toRelativeString(rootfsDir).replace('\\', '/')
    }

    /**
     * Chromium wrapper 스크립트를 생성하고 proroot 내부 경로를 반환한다.
     * OpenClaw의 browser 스키마가 .strict()라서 extraArgs를 무시하므로,
     * --no-zygote --single-process --disable-dev-shm-usage 등은
     * wrapper 스크립트로 주입해야 한다.
     */
    fun ensureChromiumWrapper(extraArgs: List<String>): String? {
        val rawPath = detectChromiumExecutablePath() ?: return null
        val rawProotPath = "/" + File(rawPath).toRelativeString(rootfsDir).replace('\\', '/')
        val wrapperName = "chromium-proroot-wrapper.sh"
        val wrapperFile = File(rawPath).parentFile?.let { File(it, wrapperName) } ?: return null
        val argsStr = extraArgs.joinToString(" ") { "\"$it\"" }
        val chromeDir = "/" + File(rawPath).parentFile!!.toRelativeString(rootfsDir).replace('\\', '/')
        val script = "#!/bin/sh\nexport LD_LIBRARY_PATH=\"$chromeDir:\${LD_LIBRARY_PATH:-}\"\nexec $rawProotPath $argsStr \"\$@\"\n"
        val existing = if (wrapperFile.exists()) wrapperFile.readText() else ""
        if (existing != script) {
            wrapperFile.writeText(script)
            wrapperFile.setExecutable(true)
        }
        return "/" + wrapperFile.toRelativeString(rootfsDir).replace('\\', '/')
    }

    private fun detectChromiumExecutablePath(): String? {
        val browserRoot = playwrightCacheDir
        val browserDirs = browserRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("chromium") }
            ?.sortedWith(
                compareByDescending<File> { it.name.startsWith("chromium_headless_shell") }
                    .thenByDescending { it.name },
            )
            ?: return null

        for (browserDir in browserDirs) {
            val headlessShell = File(browserDir, "chrome-linux/headless_shell")
            if (headlessShell.exists() && headlessShell.canExecute()) {
                return headlessShell.absolutePath
            }

            val chrome = File(browserDir, "chrome-linux/chrome")
            if (chrome.exists() && chrome.canExecute()) {
                return chrome.absolutePath
            }
        }

        return null
    }

    private fun readChromiumMarkerPath(): String? {
        return runCatching {
            if (!playwrightChromeMarkerFile.exists()) return null
            playwrightChromeMarkerFile.readText().trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun writeChromiumMarkerPath(path: String) {
        runCatching {
            playwrightChromeMarkerFile.writeText(path)
        }
    }

    private fun clearChromiumMarkerPath() {
        if (playwrightChromeMarkerFile.exists()) {
            playwrightChromeMarkerFile.delete()
        }
    }

    // ── hook library 준비 ──

    /**
     * libproroot-runtime.so를 nativeLibraryDir에서 filesDir로 복사한다.
     * rootfs의 ld-linux이 LD_PRELOAD로 로드하려면 SELinux 접근 가능한 경로가 필요.
     */
    fun setupHookLibrary() {
        val src = File(nativeHookLibPath)
        val dst = hookLibPath
        Log.d("ProrootManager", "setupHookLibrary src=${src.absolutePath} srcHash=${src.sha256OrNull()}")
        // 항상 복사 — 크기가 같아도 내용이 다를 수 있음 (빌드 업데이트 시)
        if (src.exists()) {
            dst.delete()
            src.inputStream().use { input ->
                dst.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            dst.setReadable(true, false)
            dst.setExecutable(true, false)
            Log.d("ProrootManager", "setupHookLibrary dst=${dst.absolutePath} dstHash=${dst.sha256OrNull()}")

            val guestDst = guestHookLibHostPath
            guestDst.parentFile?.mkdirs()
            runCatching {
                src.inputStream().use { input ->
                    guestDst.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                guestDst.setReadable(true, false)
                guestDst.setExecutable(true, false)
                Log.d("ProrootManager", "setupHookLibrary guestDst=${guestDst.absolutePath} guestHash=${guestDst.sha256OrNull()}")
            }


            val vforkShimSrc = File(context.filesDir, "libvfork_shim.so")
            if (vforkShimSrc.exists()) {
                val vforkShimGuestDst = guestVforkShimHostPath
                vforkShimGuestDst.parentFile?.mkdirs()
                runCatching {
                    vforkShimSrc.inputStream().use { input ->
                        vforkShimGuestDst.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    vforkShimGuestDst.setReadable(true, false)
                    vforkShimGuestDst.setExecutable(true, false)
                    Log.d("ProrootManager", "setupHookLibrary vforkShim=${vforkShimGuestDst.absolutePath} hash=${vforkShimGuestDst.sha256OrNull()}")
                }
            }
        }
    }

    private fun File.sha256OrNull(): String? {
        if (!exists()) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    // ── 명령어 구성 ──

    /** /dev/shm 에뮬레이션 디렉토리 (Chromium shared memory 용) */
    val shmDir: File
        get() = File(context.cacheDir, "shm").apply { mkdirs() }

    fun buildProrootCommand(command: String): List<String> {
        return listOf(
            prorootBinaryPath,
            "-r", rootfsDir.absolutePath,
            "-b", "/dev:/dev",
            "-b", "/proc:/proc",
            "-b", "/sys:/sys",
            "-b", "/dev/urandom:/dev/random",
            "-b", "${shmDir.absolutePath}:/dev/shm",
            "-w", "/root",
            "--link2symlink",    // Android 파일시스템에서 hardlink→symlink 변환
            "/bin/sh", "-c", command,
        )
    }

    fun buildProrootArgvCommand(argv: List<String>): List<String> {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        return buildList {
            add(prorootBinaryPath)
            add("-r")
            add(rootfsDir.absolutePath)
            add("-b")
            add("/dev:/dev")
            add("-b")
            add("/proc:/proc")
            add("-b")
            add("/sys:/sys")
            add("-b")
            add("/dev/urandom:/dev/random")
            add("-b")
            add("${shmDir.absolutePath}:/dev/shm")
            add("-w")
            add("/root")
            add("--link2symlink")
            addAll(argv)
        }
    }

    fun buildOpenClawNodeCommand(vararg args: String): List<String> {
        return buildProrootArgvCommand(
            listOf(OPENCLAW_NODE_BIN, OPENCLAW_ENTRYPOINT) + args.toList(),
        )
    }

    internal fun buildHostShellWrappedCommand(command: List<String>): List<String> {
        fun quote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"
        return listOf(
            "/system/bin/sh",
            "-c",
            command.joinToString(" ") { quote(it) },
        )
    }

    fun buildGatewayCommand(): List<String> {
        return buildProrootCommand(
                "export UV_USE_IO_URING=0 && " +
                "export NODE_OPTIONS='--require /root/.openclaw-patch.js' && " +
                "export NODE_COMPILE_CACHE=/root/.cache/node-compile-cache && " +
                "TG_IP=$(node -e \"const dns=require('dns');dns.resolve4('api.telegram.org',(e,a)=>{if(e||!a||!a.length)process.exit(1);process.stdout.write(a[0]);});\" 2>/dev/null || true); " +
                "(grep -v 'api.telegram.org' /etc/hosts 2>/dev/null; [ -n \"\$TG_IP\" ] && echo \"\$TG_IP api.telegram.org\") > /tmp/hosts.andclaw 2>/dev/null && cat /tmp/hosts.andclaw > /etc/hosts 2>/dev/null || true; " +
                "openclaw gateway run"
        )
    }

    val isOpenClawConfigured: Boolean
        get() = File(rootfsDir, "root/.openclaw/openclaw.json").exists()

    /** nativeLibraryDir의 ld-linux (libldlinux.so) — v3 ld.so execve용 */
    private val nativeLinkerPath: String
        get() = File(context.applicationInfo.nativeLibraryDir, "libldlinux.so").absolutePath

    /** nativeLibraryDir의 static trampoline — seccomp-safe child exec용 */
    private val nativeTrampolinePath: String
        get() = File(context.applicationInfo.nativeLibraryDir, "libproroot-bridge.so").absolutePath

    fun buildEnvironment(extra: Map<String, String> = emptyMap()): Map<String, String> {
        return buildMap {
            put("HOME", homeDir.absolutePath)
            put("PROROOT_LIB_PATH", nativeHookLibPath)
            put("PROROOT_LINKER_PATH", nativeLinkerPath)
            put("PROROOT_ROOTFS", rootfsDir.absolutePath)
            put("PROROOT_GUEST_EXE", OPENCLAW_NODE_BIN)
            put("PROROOT_TRAMPOLINE_PATH", nativeTrampolinePath)
            put("PROROOT_NO_SECCOMP", "1")
            put("PROROOT_FORCE_MMAP", "1")
            if (guestVforkShimHostPath.exists()) {
                put("PROROOT_GUEST_LD_PRELOAD", GUEST_VFORK_SHIM_PATH)
            }
            put("PLAYWRIGHT_BROWSERS_PATH", File(rootfsDir, "root/.cache/ms-playwright").absolutePath)
            // PROROOT_TRACE set only in ProcessManager for gateway debugging
            putAll(extra)
        }
    }

    internal fun applyEnvironment(target: MutableMap<String, String>, extra: Map<String, String> = emptyMap()) {
        target.clear()
        target.putAll(
            buildEnvironment(
                mapOf(
                    "HOME" to "/root",
                    "PATH" to "/usr/local/bin:/usr/bin:/bin",
                    "LANG" to "C.UTF-8",
                    "UV_USE_IO_URING" to "0",
                ) + extra,
            ),
        )
    }

    /**
     * proroot 안에서 명령을 실행하고 stdout 결과를 반환한다.
     */
    fun executeAndCapture(command: String): String? {
        return try {
            val cmd = buildProrootCommand(command)
            val pb = ProcessBuilder(cmd).redirectErrorStream(false)
            applyEnvironment(pb.environment())
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * proroot 안에서 명령을 실행하고 종료코드/출력을 반환한다.
     */
    fun executeWithResult(
        command: String,
        timeoutMs: Long = 300_000,
        extraEnv: Map<String, String> = emptyMap(),
        wrapInHostShell: Boolean = false,
        captureViaTempFile: Boolean = false,
    ): CommandResult? {
        return try {
            val rawCmd = buildProrootCommand(command)
            val cmd = if (wrapInHostShell) buildHostShellWrappedCommand(rawCmd) else rawCmd
            val tempOutputFile = if (captureViaTempFile) File.createTempFile("proroot-capture-", ".log", context.cacheDir) else null
            val pb = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectInput(devNull)
            applyEnvironment(pb.environment(), extraEnv)
            if (tempOutputFile != null) {
                pb.redirectOutput(tempOutputFile)
            }
            android.util.Log.d("ProrootManager", "exec: cmd_size=${cmd.size} cmd=${cmd.map { "[$it]" }}")
            android.util.Log.d("ProrootManager", "exec: PROROOT_LIB_PATH=${pb.environment()["PROROOT_LIB_PATH"]}")
            val process = pb.start()
            runCatching { process.outputStream.close() }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CommandResult(
                    exitCode = -1,
                    output = tempOutputFile?.takeIf { it.exists() }?.readText()?.trim()
                        ?: process.inputStream.bufferedReader().readText().trim(),
                    timedOut = true,
                )
            }

            val output = tempOutputFile?.takeIf { it.exists() }?.readText()?.trim()
                ?: process.inputStream.bufferedReader().readText().trim()
            android.util.Log.d("ProrootManager", "result: exit=${process.exitValue()}")
            output.lineSequence().forEach { line ->
                android.util.Log.d("ProrootManager", "  out: $line")
            }
            CommandResult(
                exitCode = process.exitValue(),
                output = output,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun executeArgvWithResult(
        argv: List<String>,
        timeoutMs: Long = 300_000,
        extraEnv: Map<String, String> = emptyMap(),
        wrapInHostShell: Boolean = false,
    ): CommandResult? {
        return try {
            val rawCmd = buildProrootArgvCommand(argv)
            val cmd = if (wrapInHostShell) buildHostShellWrappedCommand(rawCmd) else rawCmd
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectInput(devNull)
            applyEnvironment(pb.environment(), extraEnv)
            android.util.Log.d("ProrootManager", "exec(argv): cmd_size=${cmd.size} cmd=${cmd.map { "[$it]" }}")
            android.util.Log.d("ProrootManager", "exec(argv): PROROOT_LIB_PATH=${pb.environment()["PROROOT_LIB_PATH"]}")
            val process = pb.start()
            runCatching { process.outputStream.close() }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CommandResult(
                    exitCode = -1,
                    output = process.inputStream.bufferedReader().readText().trim(),
                    timedOut = true,
                )
            }

            val output = process.inputStream.bufferedReader().readText().trim()
            android.util.Log.d("ProrootManager", "result(argv): exit=${process.exitValue()}")
            output.lineSequence().forEach { line ->
                android.util.Log.d("ProrootManager", "  out(argv): $line")
            }
            CommandResult(
                exitCode = process.exitValue(),
                output = output,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * proroot 안에서 명령을 실행하며 출력 라인을 실시간으로 전달한다.
     */
    fun executeWithStreamingOutput(
        command: String,
        onLine: (String) -> Unit,
    ): CommandResult? {
        return try {
            val cmd = buildProrootCommand(command)
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectInput(devNull)
            applyEnvironment(pb.environment())
            val process = pb.start()
            runCatching { process.outputStream.close() }

            val output = StringBuilder()
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val current = line ?: continue
                    output.append(current).append('\n')
                    onLine(current)
                }
            }

            val exitCode = process.waitFor()
            CommandResult(
                exitCode = exitCode,
                output = output.toString().trim(),
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * proroot 안에서 명령을 실행하며 텍스트 조각을 실시간으로 전달한다.
     * (줄바꿈 없는 프롬프트/진행 출력 캡처용)
     */
    fun executeWithStreamingText(
        command: String,
        onChunk: (String) -> Unit,
    ): CommandResult? {
        return try {
            val cmd = buildProrootCommand(command)
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectInput(devNull)
            applyEnvironment(pb.environment())
            val process = pb.start()
            runCatching { process.outputStream.close() }

            val output = StringBuilder()
            process.inputStream.bufferedReader().use { reader ->
                val buf = CharArray(1024)
                var n: Int
                while (reader.read(buf).also { n = it } != -1) {
                    val chunk = String(buf, 0, n)
                    output.append(chunk)
                    onChunk(chunk)
                }
            }

            val exitCode = process.waitFor()
            CommandResult(
                exitCode = exitCode,
                output = output.toString().trim(),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun getAvailableStorageMb(): Long {
        val stat = android.os.StatFs(context.filesDir.absolutePath)
        return stat.availableBytes / (1024 * 1024)
    }

    fun hasEnoughStorage(): Boolean = getAvailableStorageMb() > 1500
}
    private val devNull: File = File("/dev/null")
