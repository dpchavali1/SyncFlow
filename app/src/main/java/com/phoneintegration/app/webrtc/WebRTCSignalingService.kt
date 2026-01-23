package com.phoneintegration.app.webrtc

import android.content.Context
import android.util.Log
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.phoneintegration.app.desktop.DesktopSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Handles WebRTC signaling (SDP/ICE exchange) through Firebase
 */
class WebRTCSignalingService(private val context: Context) {

    companion object {
        private const val TAG = "WebRTCSignaling"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val database = FirebaseDatabase.getInstance()
    private val syncService = DesktopSyncService(context)

    // Listeners
    private var answerListener: ChildEventListener? = null
    private var iceCandidateListener: ChildEventListener? = null

    // Callbacks
    var onAnswerReceived: ((String) -> Unit)? = null
    var onIceCandidateReceived: ((IceCandidate) -> Unit)? = null

    /**
     * Send SDP offer to desktop through Firebase
     */
    suspend fun sendOffer(callId: String, sdp: String) {
        try {
            val userId = syncService.getCurrentUserId()
            val offerRef = database.reference
                .child("users")
                .child(userId)
                .child("webrtc_signaling")
                .child(callId)
                .child("offer")

            val offerData = mapOf(
                "type" to "offer",
                "sdp" to sdp,
                "timestamp" to ServerValue.TIMESTAMP
            )

            offerRef.setValue(offerData).await()
            Log.d(TAG, "Offer sent to Firebase for call: $callId")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending offer", e)
            throw e
        }
    }

    /**
     * Send ICE candidate to desktop through Firebase
     */
    suspend fun sendIceCandidate(callId: String, candidate: IceCandidate) {
        try {
            val userId = syncService.getCurrentUserId()
            val candidateRef = database.reference
                .child("users")
                .child(userId)
                .child("webrtc_signaling")
                .child(callId)
                .child("ice_candidates_android")
                .push()

            val candidateData = mapOf(
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
                "candidate" to candidate.sdp,
                "timestamp" to ServerValue.TIMESTAMP
            )

            candidateRef.setValue(candidateData).await()
            Log.d(TAG, "ICE candidate sent to Firebase")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending ICE candidate", e)
        }
    }

    /**
     * Listen for SDP answer from desktop
     */
    fun listenForAnswer(callId: String) {
        scope.launch {
            try {
                val userId = syncService.getCurrentUserId()
                val answerRef = database.reference
                    .child("users")
                    .child(userId)
                    .child("webrtc_signaling")
                    .child(callId)
                    .child("answer")

            answerListener = object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val sdp = snapshot.child("sdp").value as? String
                    if (sdp != null) {
                        Log.d(TAG, "Answer received from desktop")
                        onAnswerReceived?.invoke(sdp)
                    }
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Error listening for answer", error.toException())
                }
            }

                answerRef.addChildEventListener(answerListener!!)
                Log.d(TAG, "Listening for answer from desktop")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up answer listener", e)
            }
        }
    }

    /**
     * Listen for ICE candidates from desktop
     */
    fun listenForIceCandidates(callId: String) {
        scope.launch {
            try {
                val userId = syncService.getCurrentUserId()
                val candidatesRef = database.reference
                    .child("users")
                    .child(userId)
                    .child("webrtc_signaling")
                    .child(callId)
                    .child("ice_candidates_desktop")

            iceCandidateListener = object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val sdpMid = snapshot.child("sdpMid").value as? String
                    val sdpMLineIndex = (snapshot.child("sdpMLineIndex").value as? Long)?.toInt() ?: 0
                    val candidateSdp = snapshot.child("candidate").value as? String

                    if (sdpMid != null && candidateSdp != null) {
                        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
                        Log.d(TAG, "ICE candidate received from desktop")
                        onIceCandidateReceived?.invoke(iceCandidate)
                    }
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Error listening for ICE candidates", error.toException())
                }
            }

                candidatesRef.addChildEventListener(iceCandidateListener!!)
                Log.d(TAG, "Listening for ICE candidates from desktop")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up ICE candidate listener", e)
            }
        }
    }

    /**
     * Clean up signaling data for a call
     */
    suspend fun cleanupSignaling(callId: String) {
        try {
            val userId = syncService.getCurrentUserId()
            val signalingRef = database.reference
                .child("users")
                .child(userId)
                .child("webrtc_signaling")
                .child(callId)

            signalingRef.removeValue().await()
            Log.d(TAG, "Signaling data cleaned up for call: $callId")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up signaling", e)
        }
    }

    /**
     * Stop listening for signaling events
     */
    fun stopListening(callId: String) {
        scope.launch {
            try {
                val userId = syncService.getCurrentUserId()
                val signalingRef = database.reference
                    .child("users")
                    .child(userId)
                    .child("webrtc_signaling")
                    .child(callId)

            answerListener?.let {
                signalingRef.child("answer").removeEventListener(it)
            }
            iceCandidateListener?.let {
                signalingRef.child("ice_candidates_desktop").removeEventListener(it)
            }

                answerListener = null
                iceCandidateListener = null

                Log.d(TAG, "Stopped listening for signaling events")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping signaling listeners", e)
            }
        }
    }
}
