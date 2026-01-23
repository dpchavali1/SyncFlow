package com.phoneintegration.app.desktop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.util.Base64
import android.util.Log
import com.google.firebase.database.ServerValue
import com.phoneintegration.app.utils.PhoneNumberNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream

/**
 * Handles syncing contacts to Firebase for desktop access
 */
class ContactsSyncService(private val context: Context) {

    private val syncService = DesktopSyncService(context)

    companion object {
        private const val TAG = "ContactsSyncService"
    }

    /**
     * Data class for contact information
     */
    data class Contact(
        val id: String,
        val displayName: String,
        val phoneNumber: String,
        val normalizedNumber: String,
        val phoneType: String?,
        val photoUri: String? = null,
        val photoBase64: String? = null,
        val email: String? = null
    )

    /**
     * Get all contacts with phone numbers
     */
    suspend fun getAllContacts(): List<Contact> = withContext(Dispatchers.IO) {
        val contacts = mutableListOf<Contact>()
        val seenNumbers = mutableSetOf<String>()

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI
        )

        try {
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val normalizedIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)
                val typeIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                val photoIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

                while (cursor.moveToNext()) {
                    val contactId = cursor.getString(idIndex) ?: continue
                    val name = cursor.getString(nameIndex) ?: "Unknown"
                    val number = cursor.getString(numberIndex) ?: continue
                    val normalized = cursor.getString(normalizedIndex) ?: number
                    val type = cursor.getInt(typeIndex)
                    val photoUri = cursor.getString(photoIndex)

                    // Skip duplicates based on normalized number
                    val uniqueKey = "$contactId:$normalized"
                    if (seenNumbers.contains(uniqueKey)) continue
                    seenNumbers.add(uniqueKey)

                    val phoneType = getPhoneTypeLabel(type)

                    // Get contact photo as Base64 (for Firebase sync)
                    val photoBase64 = photoUri?.let { getContactPhotoBase64(it) }

                    contacts.add(
                        Contact(
                            id = contactId,
                            displayName = name,
                            phoneNumber = number,
                            normalizedNumber = normalized,
                            phoneType = phoneType,
                            photoUri = photoUri,
                            photoBase64 = photoBase64
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading contacts", e)
        }

        Log.d(TAG, "Retrieved ${contacts.size} contacts")
        return@withContext contacts
    }

    /**
     * Sync all contacts to Firebase
     */
    suspend fun syncContacts() {
        try {
            val userId = syncService.getCurrentUserId()
            val contacts = getAllContacts()

            Log.d(TAG, "Syncing ${contacts.size} contacts to Firebase...")

            // Get Firebase reference
            val contactsRef = com.google.firebase.database.FirebaseDatabase.getInstance().reference
                .child("users")
                .child(userId)
                .child("contacts")

            // Get existing contacts to avoid unnecessary updates
            val existingContactsSnapshot = contactsRef.get().await()
            val existingContacts = existingContactsSnapshot.children.associate { snapshot ->
                snapshot.key to snapshot.value
            }

            val contactsMap = contacts.associate { contact ->
                // Use PhoneNumberNormalizer for consistent deduplication across all platforms
                val contactId = PhoneNumberNormalizer.getDeduplicationKey(contact.phoneNumber, contact.displayName)
                val contactData = mutableMapOf<String, Any>(
                    "id" to contact.id,
                    "displayName" to contact.displayName,
                    "phoneNumber" to contact.phoneNumber,
                    "normalizedNumber" to contact.normalizedNumber,
                    "phoneType" to (contact.phoneType ?: "Mobile"),
                    "photoUri" to (contact.photoUri ?: ""),
                    "syncedAt" to ServerValue.TIMESTAMP
                )

                // Only include photo if it's not too large (< 50KB)
                contact.photoBase64?.let { photo ->
                    if (photo.length < 50000) {
                        contactData["photoBase64"] = photo
                    }
                }

                contactId to contactData
            }

            // Only update if contacts have changed
            if (contactsMap != existingContacts) {
                // Use updateChildren instead of setValue to avoid removing all contacts first
                // This prevents the UI from flickering
                contactsRef.setValue(contactsMap).await()
                Log.d(TAG, "Successfully synced ${contacts.size} contacts to Firebase")
            } else {
                Log.d(TAG, "Contacts unchanged, skipping sync")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing contacts", e)
            throw e
        }
    }

    /**
     * Get phone type label
     */
    private fun getPhoneTypeLabel(type: Int): String {
        return when (type) {
            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
            ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "Main"
            ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "Other"
            else -> "Mobile"
        }
    }

    /**
     * Get contact photo as Base64 string
     */
    private fun getContactPhotoBase64(photoUriString: String): String? {
        return try {
            val photoUri = Uri.parse(photoUriString)
            val inputStream = context.contentResolver.openInputStream(photoUri)

            inputStream?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)

                // Resize image to reduce size (max 150x150)
                val resized = Bitmap.createScaledBitmap(
                    bitmap,
                    minOf(bitmap.width, 150),
                    minOf(bitmap.height, 150),
                    true
                )

                // Convert to Base64
                val outputStream = ByteArrayOutputStream()
                resized.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                val byteArray = outputStream.toByteArray()

                bitmap.recycle()
                resized.recycle()

                Base64.encodeToString(byteArray, Base64.DEFAULT)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading contact photo: ${e.message}")
            null
        }
    }
}
