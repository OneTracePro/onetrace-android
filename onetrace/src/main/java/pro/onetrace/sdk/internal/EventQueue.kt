package pro.onetrace.sdk.internal

import org.json.JSONObject
import java.io.File

/**
 * Events waiting to be sent, one JSON object per line in the app files directory: they survive a restart and
 * a lack of network. Beyond [maxSize] the oldest events are dropped.
 */
internal class EventQueue(private val file: File, private val maxSize: Int) {
    private val items: MutableList<String> = load()

    @Synchronized
    fun add(event: JSONObject) {
        items.add(event.toString())

        while (items.size > maxSize) {
            items.removeAt(0)
        }

        save()
    }

    @Synchronized
    fun size(): Int = items.size

    @Synchronized
    fun peek(count: Int): List<JSONObject> = items.take(count).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }

    @Synchronized
    fun remove(count: Int) {
        repeat(minOf(count, items.size)) { items.removeAt(0) }
        save()
    }

    private fun load(): MutableList<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() }.takeLast(maxSize).toMutableList() else mutableListOf()

    private fun save() {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(if (items.isEmpty()) "" else items.joinToString("\n", postfix = "\n"))

        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }
}
