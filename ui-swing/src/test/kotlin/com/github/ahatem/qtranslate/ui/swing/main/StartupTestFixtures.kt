package com.github.ahatem.qtranslate.ui.swing.main

import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.ConfigurationPersistence
import com.github.ahatem.qtranslate.core.settings.data.SettingsError
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.nio.file.Files

internal class StartupTestFixture : AutoCloseable {
    private val directory = Files.createTempDirectory("camwork-startup-проверка ")
    private val files = mutableListOf<File>()
    val launcher = file("CamWork Translate.exe")
    val registry = MemoryStartupRegistry()
    val startup = WindowsStartupRegistration(registry, { launcher }, { true })
    val command get() = StartupCommand("\"${launcher.canonicalPath}\"")

    fun file(name: String): File = Files.createFile(directory.resolve(name)).toFile().also(files::add)

    override fun close() {
        files.forEach { Files.deleteIfExists(it.toPath()) }
        Files.deleteIfExists(directory)
    }
}

internal class MemoryStartupRegistry : StartupRegistry {
    var command: StartupCommand? = null
    var approval: ByteArray? = null
    var policyDisabled = false
    var readFailure: Exception? = null
    var writeFailure: Exception? = null
    var deletionFailure: Exception? = null
    var ignoreWrites = false
    var reads = 0
    var writes = 0
    var deletes = 0

    override fun readCommand(): StartupCommand? {
        reads++
        readFailure?.let { throw it }
        return command
    }
    override fun writeCommand(command: StartupCommand) {
        writeFailure?.let { throw it }
        writes++
        if (!ignoreWrites) this.command = command
    }
    override fun deleteCommand() {
        deletionFailure?.let { throw it }
        deletes++
        command = null
    }
    override fun readApproval() = approval
    override fun isDisabledByPolicy() = policyDisabled
}

internal class StartupMemoryPersistence(initial: Configuration = Configuration.DEFAULT) : ConfigurationPersistence {
    override val configuration = MutableStateFlow(initial)
    var result: Result<Unit, SettingsError> = Ok(Unit)
    var thrown: Exception? = null
    var hang = false
    var gate: CompletableDeferred<Unit>? = null
    var entered: CompletableDeferred<Unit>? = null
    var writes = 0

    override suspend fun updateConfiguration(config: Configuration): Result<Unit, SettingsError> {
        writes++
        entered?.complete(Unit)
        gate?.await()
        if (hang) awaitCancellation()
        thrown?.let { throw it }
        if (result.isOk) configuration.value = config
        return result
    }
}

internal fun startupApproval(state: Int): ByteArray = ByteArray(12).also { it[0] = state.toByte() }
