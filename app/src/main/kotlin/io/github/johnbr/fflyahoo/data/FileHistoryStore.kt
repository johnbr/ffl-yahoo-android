package io.github.johnbr.fflyahoo.data

import android.content.Context
import io.github.johnbr.fflyahoo.core.engine.HistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A league's scoring history, as JSON in app-private storage. */
class FileHistoryStore(context: Context, leagueId: String) : HistoryStore {
    private val file = File(context.filesDir, "plays-$leagueId.json")

    override suspend fun load(): String? = withContext(Dispatchers.IO) { if (file.exists()) file.readText() else null }

    override suspend fun save(json: String) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json)
        tmp.renameTo(file)
        Unit
    }

    fun delete() {
        file.delete()
    }
}
