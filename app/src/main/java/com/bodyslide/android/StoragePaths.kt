package com.bodyslide.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

object StoragePaths {

    fun hasAllFilesAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun requestAllFilesAccess(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            } catch (_: Exception) {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    fun resolve(context: Context, uri: Uri): File? {
        if (uri.scheme == "file") {
            return uri.path?.let { File(it) }?.takeIf { it.exists() }
        }

        if (uri.scheme != "content") return null

        val authority = uri.authority ?: return null

        // Common "com.android.externalstorage.documents" tree/document URIs
        try {
            val docId = when {
                DocumentsContract.isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
                DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.getDocumentId(uri)
                else -> null
            }
            if (docId != null) {
                val split = docId.split(":")
                if (split.size >= 2) {
                    val type = split[0]
                    val relative = split.drop(1).joinToString(":")
                    val base = when (type.lowercase()) {
                        "primary" -> Environment.getExternalStorageDirectory().absolutePath
                        else -> "/storage/$type"
                    }
                    val candidate = File(base, relative)
                    if (candidate.exists()) return candidate
                }
            }
        } catch (_: Exception) {
            // fall through
        }

        // Last resort: try to map common /tree/primary: style
        val path = uri.path ?: return null
        val marker = "/tree/"
        val idx = path.indexOf(marker)
        if (idx >= 0) {
            val rest = path.substring(idx + marker.length)
            val split = rest.split(":")
            if (split.size >= 2) {
                val type = split[0]
                val relative = split.drop(1).joinToString(":")
                val base = if (type == "primary") {
                    Environment.getExternalStorageDirectory().absolutePath
                } else "/storage/$type"
                val candidate = File(base, relative)
                if (candidate.exists()) return candidate
            }
        }

        return null
    }

    fun persistPermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
    }
}
