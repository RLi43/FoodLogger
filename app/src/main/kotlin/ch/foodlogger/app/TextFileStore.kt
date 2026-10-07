package ch.foodlogger.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One text file in app-private storage (used for the recent list and the journal). */
class TextFileStore(context: Context, name: String) {

    private val file = File(context.filesDir, name)

    suspend fun read(): String = withContext(Dispatchers.IO) {
        if (file.exists()) file.readText() else ""
    }

    suspend fun write(text: String) = withContext(Dispatchers.IO) {
        file.writeText(text)
    }
}
