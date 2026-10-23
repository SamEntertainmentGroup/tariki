package com.dztrafic.carte

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

class DownloadCancelled : IOException("téléchargement annulé")

private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) count += it }
}

/** Emplacement du fichier d'itinéraires sur le téléphone et téléchargement (une seule fois pour toute l'Algérie). */
object RoutingStore {

    private const val FILE_NAME = "algeria-routing.bin"

    fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun isInstalled(context: Context): Boolean = file(context).let { it.exists() && it.length() > 1024 }

    fun delete(context: Context) {
        file(context).delete()
    }

    /**
     * Télécharge le fichier compressé et le décompresse au fil de l'eau.
     * Appel bloquant : à lancer dans un thread. [onProgress] reçoit les octets reçus et la taille totale (-1 si inconnue).
     */
    fun download(context: Context, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean) {
        val target = file(context)
        val temporary = File(context.filesDir, "$FILE_NAME.part")
        try {
            val connection = URL(BuildConfig.ROUTING_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("serveur : code ${connection.responseCode}")
                }
                val total = connection.contentLengthLong
                val counting = CountingInputStream(connection.inputStream)
                GZIPInputStream(counting, 64 * 1024).use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (isCancelled()) throw DownloadCancelled()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            onProgress(counting.count, total)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            if (!looksValid(temporary)) throw IOException("fichier d'itinéraires incomplet")
            target.delete()
            if (!temporary.renameTo(target)) throw IOException("impossible d'enregistrer le fichier")
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun looksValid(file: File): Boolean {
        if (file.length() < 1024) return false
        RandomAccessFile(file, "r").use { reader ->
            val magic = ByteArray(4)
            reader.readFully(magic)
            return magic.contentEquals("TRK1".toByteArray(Charsets.US_ASCII))
        }
    }
}
