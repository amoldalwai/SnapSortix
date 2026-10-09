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
    val relativePath: String?
)

class ScreenshotMedia(private val context: Context) {
    fun loadRecentScreenshots(maxCount: Int? = null): List<Screenshot> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
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
                        arrayOf(MediaStore.Images.Media.DATE_ADDED)
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
                        val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                        val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                        while (cursor.moveToNext()) {
                            fetchedInPage += 1
                            add(
                                Screenshot(
                                    imageId = cursor.getLong(idIndex).toString(),
                                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex)),
                                    displayName = cursor.getString(nameIndex),
                                    relativePath = cursor.getString(pathIndex)
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