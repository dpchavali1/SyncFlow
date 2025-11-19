package com.phoneintegration.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object MmsHelper {

    private const val TAG = "MmsHelper"

    /**
     * Public API: Sends MMS with a single image attachment
     */
    fun sendMms(
        ctx: Context,
        address: String,
        sourceUri: Uri,
        subject: String? = null
    ): Boolean {
        return try {
            Log.d(TAG, "=== Starting MMS send ===")
            Log.d(TAG, "To: $address")
            Log.d(TAG, "Source URI: $sourceUri")

            // Check if app is default SMS app
            val isDefault = SmsPermissions.isDefaultSmsApp(ctx)
            Log.d(TAG, "Is default SMS app: $isDefault")
            if (!isDefault) {
                Log.w(TAG, "⚠ WARNING: App is NOT set as default SMS app. MMS may fail!")
            }

            // 1. Copy the picked file into app cache (required by carrier)
            val file = copyUriToCache(ctx, sourceUri)
            Log.d(TAG, "File copied to cache: ${file.absolutePath} (${file.length()} bytes)")

            // 2. Convert to content:// URI through FileProvider
            val contentUri = FileProvider.getUriForFile(
                ctx,
                ctx.packageName + ".provider",
                file
            )
            Log.d(TAG, "FileProvider URI: $contentUri")

            // 3. Grant read permission to system for MMS access
            ctx.grantUriPermission(
                "com.android.mms",
                contentUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            Log.d(TAG, "URI permissions granted to com.android.mms")

            val smsManager = SmsManager.getDefault()

            // 4. Optional overrides
            val configOverrides = Bundle().apply {
                putString(SmsManager.MMS_CONFIG_HTTP_PARAMS, "")
            }

            // 5. Callback for success/failure
            val sentPI = PendingIntent.getBroadcast(
                ctx, 0,
                Intent("MMS_SENT").setClass(ctx, com.phoneintegration.app.mms.MmsSentReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val deliveredPI = PendingIntent.getBroadcast(
                ctx, 1,
                Intent("MMS_DELIVERED").setClass(ctx, com.phoneintegration.app.mms.MmsDeliveredReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            Log.d(TAG, "Calling sendMultimediaMessage()...")
            smsManager.sendMultimediaMessage(
                ctx,
                contentUri,
                null,
                configOverrides,
                sentPI
            )

            Log.d(TAG, "✓ sendMultimediaMessage() call completed")
            true

        } catch (e: Exception) {
            Log.e(TAG, "✗ MMS SEND FAILED - Exception: ${e.message}", e)
            e.printStackTrace()
            false
        }
    }

    /**
     * Carrier requires file to be local: /data/data/<package>/cache/...
     */
    private fun copyUriToCache(ctx: Context, uri: Uri): File {
        val file = File(ctx.cacheDir, "mms_${System.currentTimeMillis()}.jpg")

        ctx.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }

        return file
    }

    /**
     * ----- MMS Reading (unchanged) -----
     */
    fun getMmsAddress(contentResolver: android.content.ContentResolver, mmsId: Long): String? {
        val uri = Uri.parse("content://mms/$mmsId/addr")
        val cursor = contentResolver.query(
            uri,
            arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
            null, null, null
        )

        cursor?.use {
            while (it.moveToNext()) {
                val address = it.getString(it.getColumnIndexOrThrow(Telephony.Mms.Addr.ADDRESS))
                val type = it.getInt(it.getColumnIndexOrThrow(Telephony.Mms.Addr.TYPE))
                if (!address.isNullOrBlank() && (type == 137 || type == 151))
                    return address
            }
        }
        return null
    }

    fun getMmsText(contentResolver: android.content.ContentResolver, mmsId: Long): String? {
        val uri = Uri.parse("content://mms/part")
        val cursor = contentResolver.query(
            uri,
            arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT),
            "${Telephony.Mms.Part.MSG_ID} = ?",
            arrayOf(mmsId.toString()),
            null
        )

        val textBuilder = StringBuilder()
        cursor?.use {
            while (it.moveToNext()) {
                if (it.getString(it.getColumnIndexOrThrow(Telephony.Mms.Part.CONTENT_TYPE)) == "text/plain") {
                    val text = it.getString(it.getColumnIndexOrThrow(Telephony.Mms.Part.TEXT))
                    if (!text.isNullOrBlank()) textBuilder.append(text)
                }
            }
        }

        return textBuilder.toString().ifBlank { null }
    }
}
