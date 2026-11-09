package com.github.ahatem.qtranslate.ui.swing.main

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemStartupRegistrationTest {
    @Test
    fun `jpackage launcher имеет приоритет перед javaw и резервным путём процесса`() = StartupTestFixture().use { fixture ->
        val javaw = fixture.file("javaw.exe")
        val java = fixture.file("java.exe")
        val other = fixture.file("Other.exe")
        assertEquals(fixture.launcher.canonicalFile, resolveStartupLauncher(fixture.launcher.path, javaw.path))
        assertEquals(fixture.launcher.canonicalFile, resolveStartupLauncher(fixture.launcher.path, other.path))
        assertEquals(other.canonicalFile, resolveStartupLauncher("несуществующий.exe", other.path))
        assertNull(resolveStartupLauncher(null, javaw.path))
        assertNull(resolveStartupLauncher(java.path, javaw.path))
        assertNull(resolveStartupLauncher("\"${fixture.launcher.path}\"", javaw.path))
    }

    @Test
    fun `команда Run сохраняет пробелы и кириллицу внутри одной пары кавычек`() = StartupTestFixture().use { fixture ->
        val mutation = expectMutation(fixture.startup.change(true))
        assertEquals(fixture.command, fixture.registry.command)
        assertEquals("\"${fixture.launcher.canonicalPath}\"", fixture.registry.command?.value)
        assertTrue(fixture.startup.inspect().enabled)
        assertTrue(mutation.changed)
        assertFalse(expectMutation(fixture.startup.change(true)).changed)
        assertEquals(1, fixture.registry.writes)
    }

    @Test
    fun `отключение Windows и неизвестный формат не выдаются за успешное включение`() = StartupTestFixture().use { fixture ->
        for (state in listOf(3, 7)) {
            fixture.registry.approval = startupApproval(state)
            assertEquals(StartupFailure.DISABLED_BY_WINDOWS, expectFailure(fixture.startup.change(true)))
        }
        fixture.registry.approval = byteArrayOf(2)
        assertEquals(StartupFailure.UNKNOWN_WINDOWS_STATE, expectFailure(fixture.startup.change(true)))
        fixture.registry.approval = startupApproval(255)
        assertEquals(StartupFailure.UNKNOWN_WINDOWS_STATE, expectFailure(fixture.startup.change(true)))
        assertEquals(0, fixture.registry.writes)
        for (state in listOf(0, 2, 6)) {
            fixture.registry.approval = startupApproval(state)
            assertTrue(fixture.startup.change(true).isOk)
            assertTrue(fixture.startup.inspect().enabled)
        }
    }

    @Test
    fun `политика Windows блокирует запись а её снятие позволяет повторить операцию`() = StartupTestFixture().use { fixture ->
        fixture.registry.policyDisabled = true
        assertEquals(StartupFailure.DISABLED_BY_POLICY, expectFailure(fixture.startup.change(true)))
        assertNull(fixture.registry.command)
        fixture.registry.policyDisabled = false
        assertTrue(fixture.startup.change(true).isOk)
    }

    @Test
    fun `отказ записи и неприменённая запись обнаруживаются проверкой результата`() = StartupTestFixture().use { fixture ->
        fixture.registry.writeFailure = SecurityException("Отказ записи")
        assertEquals(StartupFailure.REGISTRY_DENIED, expectFailure(fixture.startup.change(true)))
        fixture.registry.writeFailure = null
        fixture.registry.ignoreWrites = true
        assertEquals(StartupFailure.VERIFICATION_FAILED, expectFailure(fixture.startup.change(true)))
        fixture.registry.ignoreWrites = false
        assertTrue(fixture.startup.change(true).isOk)
        assertTrue(fixture.startup.inspect().enabled)
    }

    @Test
    fun `выключение удаляет только запись текущей копии включая аргументы запуска`() = StartupTestFixture().use { fixture ->
        val foreign = StartupCommand("\"C:\\Другая копия\\CamWork Translate.exe\" --hidden")
        fixture.registry.command = foreign
        assertTrue(fixture.startup.change(false).isOk)
        assertEquals(foreign, fixture.registry.command)
        assertEquals(0, fixture.registry.deletes)
        fixture.registry.command = StartupCommand(fixture.command.value + " --hidden")
        assertTrue(fixture.startup.change(false).isOk)
        assertNull(fixture.registry.command)
        assertEquals(1, fixture.registry.deletes)
    }

    @Test
    fun `откат сохраняет чужую команду и её REG_EXPAND_SZ а новое чужое изменение не затирает`() = StartupTestFixture().use { fixture ->
        val previous = StartupCommand("\"%LOCALAPPDATA%\\Другая копия\\CamWork Translate.exe\" --hidden", expandable = true)
        fixture.registry.command = previous
        val mutation = expectMutation(fixture.startup.change(true))
        assertNull(fixture.startup.rollback(mutation))
        assertEquals(previous, fixture.registry.command)
        val next = expectMutation(fixture.startup.change(true))
        val externallyChanged = StartupCommand("\"D:\\Новая копия\\CamWork Translate.exe\"")
        fixture.registry.command = externallyChanged
        assertEquals(StartupFailure.ROLLBACK_FAILED, fixture.startup.rollback(next))
        assertEquals(externallyChanged, fixture.registry.command)
    }

    @Test
    fun `без launcher и вне Windows запись установленной копии не затрагивается`() = StartupTestFixture().use { fixture ->
        fixture.registry.command = fixture.command
        val sourceRun = WindowsStartupRegistration(fixture.registry, { null }, { true })
        assertEquals(StartupFailure.NO_LAUNCHER, expectFailure(sourceRun.change(true)))
        assertTrue(sourceRun.change(false).isOk)
        val unsupported = WindowsStartupRegistration(fixture.registry, { fixture.launcher }, { false })
        assertEquals(StartupFailure.UNSUPPORTED_OS, expectFailure(unsupported.change(true)))
        assertTrue(unsupported.change(false).isOk)
        assertEquals(0, fixture.registry.reads)
        assertEquals(fixture.command, fixture.registry.command)
    }

    private fun expectMutation(result: Result<StartupMutation, StartupFailure>): StartupMutation {
        return result.fold(
            success = { it },
            failure = { error("Ожидался успех, получена ошибка: $it") }
        )
    }

    private fun expectFailure(result: Result<StartupMutation, StartupFailure>): StartupFailure {
        return result.fold(
            success = { error("Ожидалась ошибка, получен успех: $it") },
            failure = { it }
        )
    }
}
