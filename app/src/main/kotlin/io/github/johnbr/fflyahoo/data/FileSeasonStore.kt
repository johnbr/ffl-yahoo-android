package io.github.johnbr.fflyahoo.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A league's finished weeks' projections, kept so a season is not fetched again after a restart. */
class FileSeasonStore(context: Context, leagueId: String) {
    private val file = File(context.filesDir, "season-$leagueId.json")

    suspend fun load(): String? = withContext(Dispatchers.IO) { if (file.exists()) file.readText() else null }

    suspend fun save(json: String) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json)
        tmp.renameTo(file)
        Unit
    }

    fun delete() {
        file.delete()
    }
}
