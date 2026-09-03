package com.boostt1d.android.food

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Where the system camera writes a capture. A private cache file exposed through the app's
 * FileProvider — never a public gallery URI, since a meal photo is health data.
 */
object FoodCapture {
    fun newUri(context: Context): Uri? = runCatching {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        // One file, overwritten: the capture is read straight into a bitmap and only the
        // thumbnail is kept, so nothing needs to survive here.
        val file = File(dir, "meal.jpg")
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}
