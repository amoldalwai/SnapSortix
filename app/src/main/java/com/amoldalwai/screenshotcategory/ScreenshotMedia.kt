package com.amoldalwai.screenshotcategory

import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore

data class Screenshot(
    val imageId: String,
    val uri: Uri,
    val displayName: String,
    val relativePath: String?,
    val createdAtMs: Long?
)

class ScreenshotMedia(private val context: Context) {
    fun loadRecentScreenshots(maxCount: Int? = null): List<Screenshot> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        val selection = "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR " +
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ? OR " +
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?)"
        val selectionArgs = arrayOf("%Screenshot%", "%Screenshot%", "Screenshot%")
        val pageSize = 500

        return buildList {
            val seenUris = HashSet<String>()
            var offset = 0
            var keepLoading = true

            while (keepLoading) {
                val remaining = maxCount?.let { it - size }
                if (remaining != null && remaining <= 0) break

                val currentPageSize = remaining?.coerceAtMost(pageSize) ?: pageSize
                val queryArgs = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                    putStringArray(
                        ContentResolver.QUERY_ARG_SORT_COLUMNS,
                        arrayOf(
                            MediaStore.Images.Media.DATE_TAKEN,
                            MediaStore.Images.Media.DATE_ADDED,
                            MediaStore.Images.Media._ID
                        )
                    )
                    putInt(
                        ContentResolver.QUERY_ARG_SORT_DIRECTION,
                        ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                    )
                    putInt(ContentResolver.QUERY_ARG_LIMIT, currentPageSize)
                    putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                }

                var fetchedInPage = 0
                context.contentResolver.query(collection, projection, queryArgs, null)
                    ?.use { cursor ->
                        val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val dateTakenIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                        val dateAddedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                        val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                        val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                        while (cursor.moveToNext()) {
                            fetchedInPage += 1
                            val imageId = cursor.getLong(idIndex)
                            val dateTakenMs = cursor.getLong(dateTakenIndex)
                            val dateAddedSec = cursor.getLong(dateAddedIndex)
                            val createdAtMs = when {
                                dateTakenMs > 0L -> dateTakenMs
                                dateAddedSec > 0L -> dateAddedSec * 1000L
                                else -> null
                            }
                            val imageUri = ContentUris.withAppendedId(collection, imageId)
                            val uriKey = imageUri.toString()
                            if (!seenUris.add(uriKey)) {
                                continue
                            }
                            add(
                                Screenshot(
                                    imageId = imageId.toString(),
                                    uri = imageUri,
                                    displayName = cursor.getString(nameIndex),
                                    relativePath = cursor.getString(pathIndex),
                                    createdAtMs = createdAtMs
                                )
                            )
                        }
                    }

                if (fetchedInPage < currentPageSize) {
                    keepLoading = false
                } else {
                    offset += fetchedInPage
                }
            }
        }
    }
}