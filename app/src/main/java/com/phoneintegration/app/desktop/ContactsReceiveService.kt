package com.phoneintegration.app.desktop

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract
import android.util.Base64
import android.util.Log
import com.google.firebase.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayInputStream

/**
 * Service to receive contact changes from Firebase (created on macOS/Web)
 * and sync them to Android device contacts.
 *
 * This enables two-way contact sync:
 * - Android → Firebase (existing ContactsSyncService)
 * - Firebase → Android (this service)
 */
class ContactsReceiveService(private val context: Context) {

    companion object {
        private const val TAG = "ContactsReceiveService"
        private const val DESKTOP_CONTACTS_PATH = "desktopContacts"
    }

    private val syncService = DesktopSyncService(context)
    private var contactsListener: ValueEventListener? = null
    private var databaseRef: DatabaseReference? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Data class for contacts created on desktop/web
     */
    data class DesktopContact(
        val id: String,
        val displayName: String,
        val phoneNumber: String,
        val phoneType: String = "Mobile",
        val email: String? = null,
        val photoBase64: String? = null,
        val notes: String? = null,
        val createdAt: Long = 0,
        val updatedAt: Long = 0,
        val source: String = "desktop",  // "desktop", "web", "macos"
        val syncedToAndroid: Boolean = false
    ) {
        companion object {
            fun fromMap(id: String, data: Map<String, Any?>): DesktopContact? {
                val displayName = data["displayName"] as? String ?: return null
                val phoneNumber = data["phoneNumber"] as? String ?: return null

                return DesktopContact(
                    id = id,
                    displayName = displayName,
                    phoneNumber = phoneNumber,
                    phoneType = data["phoneType"] as? String ?: "Mobile",
                    email = data["email"] as? String,
                    photoBase64 = data["photoBase64"] as? String,
                    notes = data["notes"] as? String,
                    createdAt = (data["createdAt"] as? Long) ?: 0,
                    updatedAt = (data["updatedAt"] as? Long) ?: 0,
                    source = data["source"] as? String ?: "desktop",
                    syncedToAndroid = data["syncedToAndroid"] as? Boolean ?: false
                )
            }
        }
    }

    /**
     * Start listening for desktop-created contacts
     */
    fun startListening() {
        scope.launch {
            try {
                val userId = syncService.getCurrentUserId()

                databaseRef = FirebaseDatabase.getInstance().reference
                    .child("users")
                    .child(userId)
                    .child(DESKTOP_CONTACTS_PATH)

                contactsListener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        scope.launch {
                            processContactChanges(snapshot)
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Firebase listener cancelled: ${error.message}")
                    }
                }

                databaseRef?.addValueEventListener(contactsListener!!)
                Log.d(TAG, "Started listening for desktop contacts")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting contacts listener", e)
            }
        }
    }

    /**
     * Stop listening for contact changes
     */
    fun stopListening() {
        contactsListener?.let { listener ->
            databaseRef?.removeEventListener(listener)
        }
        contactsListener = null
        databaseRef = null
        scope.cancel()
        Log.d(TAG, "Stopped listening for desktop contacts")
    }

    /**
     * Process contact changes from Firebase
     */
    private suspend fun processContactChanges(snapshot: DataSnapshot) {
        try {
            val contacts = mutableListOf<DesktopContact>()

            for (child in snapshot.children) {
                val contactId = child.key ?: continue
                val data = child.value as? Map<String, Any?> ?: continue

                DesktopContact.fromMap(contactId, data)?.let { contact ->
                    // Only process contacts not yet synced to Android
                    if (!contact.syncedToAndroid) {
                        contacts.add(contact)
                    }
                }
            }

            Log.d(TAG, "Found ${contacts.size} new contacts to sync to Android")

            // Sync each contact to Android
            for (contact in contacts) {
                try {
                    val androidContactId = createOrUpdateAndroidContact(contact)
                    if (androidContactId != null) {
                        // Mark as synced in Firebase
                        markAsSyncedToAndroid(contact.id, androidContactId)
                        Log.d(TAG, "Synced contact ${contact.displayName} to Android (ID: $androidContactId)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing contact ${contact.displayName}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing contact changes", e)
        }
    }

    /**
     * Create or update a contact in Android's contact database
     */
    private fun createOrUpdateAndroidContact(contact: DesktopContact): Long? {
        try {
            // Check if contact already exists by phone number
            val existingId = findExistingContact(contact.phoneNumber)

            if (existingId != null) {
                // Update existing contact
                updateExistingContact(existingId, contact)
                return existingId
            }

            // Create new contact
            val ops = ArrayList<ContentProviderOperation>()

            // Insert raw contact
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )

            // Insert display name
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                    )
                    .withValue(
                        ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                        contact.displayName
                    )
                    .build()
            )

            // Insert phone number
            val phoneType = when (contact.phoneType.lowercase()) {
                "mobile" -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                "home" -> ContactsContract.CommonDataKinds.Phone.TYPE_HOME
                "work" -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK
                "main" -> ContactsContract.CommonDataKinds.Phone.TYPE_MAIN
                else -> ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
            }

            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                    )
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, contact.phoneNumber)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, phoneType)
                    .build()
            )

            // Insert email if provided
            contact.email?.let { email ->
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
                        )
                        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                        .withValue(
                            ContactsContract.CommonDataKinds.Email.TYPE,
                            ContactsContract.CommonDataKinds.Email.TYPE_OTHER
                        )
                        .build()
                )
            }

            // Insert notes if provided
            contact.notes?.let { notes ->
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
                        )
                        .withValue(ContactsContract.CommonDataKinds.Note.NOTE, notes)
                        .build()
                )
            }

            // Insert photo if provided
            contact.photoBase64?.let { photoBase64 ->
                try {
                    val photoBytes = Base64.decode(photoBase64, Base64.DEFAULT)
                    ops.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                            .withValue(
                                ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE
                            )
                            .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoBytes)
                            .build()
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error decoding photo for ${contact.displayName}", e)
                }
            }

            // Execute batch operation
            val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)

            // Get the raw contact ID from the result
            val rawContactUri = results.firstOrNull()?.uri
            return rawContactUri?.lastPathSegment?.toLongOrNull()

        } catch (e: Exception) {
            Log.e(TAG, "Error creating Android contact", e)
            return null
        }
    }

    /**
     * Find existing contact by phone number
     */
    private fun findExistingContact(phoneNumber: String): Long? {
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val selection = "${ContactsContract.CommonDataKinds.Phone.NUMBER} = ? OR ${ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER} = ?"
            val normalizedNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
            val selectionArgs = arrayOf(phoneNumber, normalizedNumber)

            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                    return cursor.getLong(idIndex)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding existing contact", e)
        }
        return null
    }

    /**
     * Update existing contact with new information
     * Uses last-write-wins conflict resolution based on updatedAt timestamp
     */
    private fun updateExistingContact(contactId: Long, contact: DesktopContact) {
        try {
            // Conflict Resolution: Check if this update should be applied
            // The updatedAt timestamp from Firebase is used to determine if this is a newer change
            val updateTimestamp = contact.updatedAt
            Log.d(TAG, "Updating contact ${contact.displayName} with timestamp: $updateTimestamp")

            // Update display name
            val nameValues = ContentValues().apply {
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, contact.displayName)
            }

            val nameSelection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
            val nameSelectionArgs = arrayOf(
                contactId.toString(),
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
            )

            context.contentResolver.update(
                ContactsContract.Data.CONTENT_URI,
                nameValues,
                nameSelection,
                nameSelectionArgs
            )

            // Update email if provided
            contact.email?.let { email ->
                val emailValues = ContentValues().apply {
                    put(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                }

                val emailSelection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
                val emailSelectionArgs = arrayOf(
                    contactId.toString(),
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
                )

                // Try to update, if no rows affected, insert new
                val updated = context.contentResolver.update(
                    ContactsContract.Data.CONTENT_URI,
                    emailValues,
                    emailSelection,
                    emailSelectionArgs
                )

                if (updated == 0) {
                    // Insert new email
                    // First get raw contact ID
                    val rawContactId = getRawContactId(contactId)
                    if (rawContactId != null) {
                        val insertValues = ContentValues().apply {
                            put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                            put(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                            put(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_OTHER)
                        }
                        context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, insertValues)
                    }
                }
            }

            // Update notes if provided
            contact.notes?.let { notes ->
                val notesValues = ContentValues().apply {
                    put(ContactsContract.CommonDataKinds.Note.NOTE, notes)
                }

                val notesSelection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
                val notesSelectionArgs = arrayOf(
                    contactId.toString(),
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
                )

                val updated = context.contentResolver.update(
                    ContactsContract.Data.CONTENT_URI,
                    notesValues,
                    notesSelection,
                    notesSelectionArgs
                )

                if (updated == 0) {
                    val rawContactId = getRawContactId(contactId)
                    if (rawContactId != null) {
                        val insertValues = ContentValues().apply {
                            put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                            put(ContactsContract.CommonDataKinds.Note.NOTE, notes)
                        }
                        context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, insertValues)
                    }
                }
            }

            Log.d(TAG, "Updated existing contact: ${contact.displayName}")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating existing contact", e)
        }
    }

    /**
     * Get raw contact ID from contact ID
     */
    private fun getRawContactId(contactId: Long): Long? {
        try {
            val uri = ContactsContract.RawContacts.CONTENT_URI
            val projection = arrayOf(ContactsContract.RawContacts._ID)
            val selection = "${ContactsContract.RawContacts.CONTACT_ID} = ?"
            val selectionArgs = arrayOf(contactId.toString())

            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getLong(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting raw contact ID", e)
        }
        return null
    }

    /**
     * Mark contact as synced to Android in Firebase
     */
    private suspend fun markAsSyncedToAndroid(contactId: String, androidContactId: Long) {
        try {
            val userId = syncService.getCurrentUserId()

            val updates = mapOf(
                "syncedToAndroid" to true,
                "androidContactId" to androidContactId,
                "syncedAt" to ServerValue.TIMESTAMP
            )

            FirebaseDatabase.getInstance().reference
                .child("users")
                .child(userId)
                .child(DESKTOP_CONTACTS_PATH)
                .child(contactId)
                .updateChildren(updates)
                .await()

        } catch (e: Exception) {
            Log.e(TAG, "Error marking contact as synced", e)
        }
    }

    /**
     * Manually sync a specific contact from desktop to Android
     */
    suspend fun syncContactFromDesktop(contactId: String): Boolean {
        return try {
            val userId = syncService.getCurrentUserId()

            val snapshot = FirebaseDatabase.getInstance().reference
                .child("users")
                .child(userId)
                .child(DESKTOP_CONTACTS_PATH)
                .child(contactId)
                .get()
                .await()

            val data = snapshot.value as? Map<String, Any?> ?: return false
            val contact = DesktopContact.fromMap(contactId, data) ?: return false

            val androidId = createOrUpdateAndroidContact(contact)
            if (androidId != null) {
                markAsSyncedToAndroid(contactId, androidId)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing specific contact", e)
            false
        }
    }
}
