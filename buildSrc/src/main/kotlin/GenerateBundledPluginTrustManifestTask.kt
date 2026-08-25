import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Создаёт неизменяемый allowlist SHA-256 штатных плагинов для конкретной сборки приложения. */
@CacheableTask
abstract class GenerateBundledPluginTrustManifestTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pluginJars: ConfigurableFileCollection

    @get:Input
    abstract val pluginIdsByArchiveName: MapProperty<String, String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val idsByName = pluginIdsByArchiveName.get()
        val entries = pluginJars.files.map { jar ->
            val pluginId = requireNotNull(idsByName[jar.name]) {
                "Для штатного плагина ${jar.name} не задан идентификатор"
            }
            "$pluginId=${sha256(jar)}"
        }.sorted()

        require(entries.map { it.substringBefore('=') }.distinct().size == entries.size) {
            "В allowlist штатных плагинов обнаружены повторяющиеся идентификаторы"
        }

        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(entries.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        }
    }

    private fun sha256(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
