/*
 * Copyright 2010-2026 Eric Kok et al.
 *
 * Transdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Transdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Transdroid. If not, see <https://www.gnu.org/licenses/>.
 */
package org.transdroid.ocr

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A search that was worked out from a shared screenshot. */
data class PendingSearch(
    val query: String,
    /** True when a show and episode were recognised, so the search can start right away. */
    val autoRun: Boolean,
)

/** Turns a shared screenshot into a search, e.g. a "Gold Rush S17E07" calendar notification into "Gold Rush S17E07 1080p". */
object ScreenshotSearch {

    /** Reads the text in the image on the device (no network); one string per block of text, top to bottom. */
    suspend fun readBlocks(context: Context, uri: Uri): List<String> {
        // ML Kit normally starts itself through a content provider; make sure it did
        runCatching { com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(context.applicationContext) }
        val image = loadImage(context, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            return suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        cont.resume(text.textBlocks.map { it.text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim() })
                    }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } finally {
            recognizer.close()
        }
    }

    /** Opens the shared image; if ML Kit's own loader can't, decodes it here (shrunk if huge) instead. */
    private fun loadImage(context: Context, uri: Uri): InputImage = try {
        InputImage.fromFilePath(context, uri)
    } catch (first: Exception) {
        Log.w("ScreenshotSearch", "InputImage.fromFilePath failed, decoding manually", first)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 2400 || bounds.outHeight / sample > 4800) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalStateException("could not open or decode the image", first)
        InputImage.fromBitmap(bitmap, 0)
    }

    private val episode = Regex("""\bS\d{1,2}\s?E\d{1,3}\b""", RegexOption.IGNORE_CASE)
    private val subjectPrefix = Regex(
        """^\s*(?:updated\s+)?(?:notification|reminder|invitation|event|alert)\s*:\s*""",
        RegexOption.IGNORE_CASE,
    )

    private val anyPrefix = Regex("""(?:notification|reminder|invitation|event|alert)\s*:\s*""", RegexOption.IGNORE_CASE)

    /**
     * The first block with an episode code (S17E07) gives the show: the words before the code, without a
     * leading "Notification:". The result is "Show S17E07", plus [quality] when it isn't blank. With no
     * episode code anywhere, the first block of text is offered as a query to edit instead.
     */
    fun buildSearch(blocks: List<String>, quality: String): PendingSearch? {
        // A long subject can be split over several text blocks, so first look at all the text together,
        // starting after the last "Notification:"-style prefix before the episode code
        val all = blocks.joinToString(" ")
        episode.find(all)?.let { match ->
            val before = all.substring(0, match.range.first)
            val prefix = anyPrefix.findAll(before).lastOrNull()
            if (prefix != null) {
                val title = before.substring(prefix.range.last + 1).trim().trimEnd('-', '–', '—', ':', ' ')
                if (title.isNotEmpty()) {
                    val code = match.value.replace(" ", "").uppercase()
                    return PendingSearch(listOf(title, code, quality.trim()).filter { it.isNotEmpty() }.joinToString(" "), autoRun = true)
                }
            }
        }
        for (block in blocks) {
            val match = episode.find(block) ?: continue
            val title = block.substring(0, match.range.first)
                .replace(subjectPrefix, "")
                .trim()
                .trimEnd('-', '–', '—', ':', ' ')
            if (title.isEmpty()) continue
            val code = match.value.replace(" ", "").uppercase()
            val query = listOf(title, code, quality.trim()).filter { it.isNotEmpty() }.joinToString(" ")
            return PendingSearch(query, autoRun = true)
        }
        val first = blocks.firstOrNull { it.isNotBlank() } ?: return null
        return PendingSearch(first.replace(subjectPrefix, "").take(80), autoRun = false)
    }
}
