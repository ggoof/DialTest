package com.fscom.dialtest

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.fscom.dialtest.ui.theme.DialTestTheme
import android.view.KeyEvent
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.PhoneStateListener
import android.telecom.TelecomManager
import java.lang.reflect.Method
import android.widget.TextView
import android.app.PictureInPictureParams
import android.content.ContentValues.TAG
import android.util.Rational
import android.content.res.Configuration
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView

class MainActivity : ComponentActivity() {
    private lateinit var deviceNumberEdit: EditText
    private lateinit var outgoingNumberEdit: EditText
    private lateinit var callTimesEdit: EditText
    private lateinit var callIntervalEdit: EditText
    private lateinit var dialButton: Button
    private lateinit var callStatusLog: TextView
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var telecomManager: TelecomManager
    private lateinit var autoAnswerCheckbox: CheckBox
    private var isAutoAnswerEnabled = false
    private var wasRinging = false
    private lateinit var logScrollView: ScrollView

    private val requiredPermissions: Array<String>
        get() = mutableListOf<String>().apply {
            //for TelephonyManager getLine1Number
            add(Manifest.permission.READ_PHONE_NUMBERS)
            //for SubscriptionInfo
            add(Manifest.permission.READ_PHONE_STATE)

            add(Manifest.permission.CALL_PHONE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Manifest.permission.ANSWER_PHONE_CALLS)
            }
        }.toTypedArray()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = requiredPermissions.all { results[it] == true }
        if (allGranted) {
            updateDeviceNumber()
            setupPhoneStateListener()
            makePhoneCallIfReady()
        } else {
            Toast.makeText(this, "All permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    private var currentCallTimes = 0
    private var totalCallTimes = 0
    private var callIntervalSec: Long = 30L
    private var outgoingNumber = ""
    private val handler = Handler(Looper.getMainLooper())

    // Track last state to suppress duplicate IDLE logs
    private var lastCallState = TelephonyManager.CALL_STATE_IDLE

    /** Appends a new line, or replaces "Ready" on first log, then scrolls down */
    private fun appendLog(message: String) {
        val current = callStatusLog.text.toString()
        if (current.isBlank() || current == "Ready") {
            callStatusLog.text = message
        } else {
            callStatusLog.append("\n$message")
        }
        // scroll to bottom
        logScrollView.post {
            logScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    /** Listen for each call-state transition and log it */
    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, incomingNumber: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    appendLog("Call $currentCallTimes: Ringing (${incomingNumber ?: "?"})")
                    if (isAutoAnswerEnabled) {
                        wasRinging = true
                        answerPhoneCall()
                    }
                }
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    appendLog("Call $currentCallTimes: Connected")
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (lastCallState != TelephonyManager.CALL_STATE_IDLE) {
                        appendLog("Call $currentCallTimes: Disconnected")
                    }
                    if (wasRinging) {
                        wasRinging = false
                        // Optional: Add any post-call handling here
                    }
                }
            }
            lastCallState = state
        }
    }

    private var isCallingSequence = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Initialize views
        deviceNumberEdit = findViewById(R.id.deviceNumberEdit)
        outgoingNumberEdit = findViewById(R.id.outgoingNumberEdit)
        callTimesEdit = findViewById(R.id.callTimesEdit)
        callIntervalEdit   = findViewById(R.id.callIntervalEdit)
        dialButton = findViewById(R.id.dialButton)
        callStatusLog = findViewById(R.id.callStatusLog)
        autoAnswerCheckbox = findViewById(R.id.autoAnswerCheckbox)
        logScrollView   = findViewById(R.id.logScrollView)

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager

        // 1) Kick off permission flow (this will call setupPhoneListener() if already granted)
        checkAndRequestPermissions()

        // Set up dial button click listener
        dialButton.setOnClickListener {
            if (!isCallingSequence) startCallingSequence()
            else               stopCallingSequence()
        }

        autoAnswerCheckbox.setOnCheckedChangeListener { _, checked ->
            isAutoAnswerEnabled = checked
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        teardownPhoneStateListener()
    }

    private fun checkAndRequestPermissions() {
        if (!hasAllPermissions()) {
            requestPermissionLauncher.launch(requiredPermissions)
        } else {
            updateDeviceNumber()
            setupPhoneStateListener()
        }
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Register the PhoneStateListener only once we have READ_PHONE_STATE permission */
    private fun setupPhoneStateListener() {
        // determine which "read" permission to check
        val readPerm = Manifest.permission.READ_PHONE_STATE

        if (ContextCompat.checkSelfPermission(this, readPerm)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // not granted yet, go back into the permission flow
            requestPermissionLauncher.launch(requiredPermissions)
            return
        }

        // now safe to register listener
        // this will work if you have READ_PHONE_STATE
        telephonyManager.listen(
            phoneStateListener,
            PhoneStateListener.LISTEN_CALL_STATE
            )
    }

    /** Unregister listener */
    private fun teardownPhoneStateListener() {
        telephonyManager.listen(
            phoneStateListener,
            PhoneStateListener.LISTEN_NONE
        )
    }

    private fun startCallingSequence() {
        outgoingNumber  = outgoingNumberEdit.text.toString().trim()
        totalCallTimes  = callTimesEdit.text.toString().toIntOrNull() ?: 1
        callIntervalSec = callIntervalEdit.text.toString().toLongOrNull() ?: 30L
        currentCallTimes = 0
        isCallingSequence = true

        dialButton.text = "Stop"
        callStatusLog.text = "Starting… (0/$totalCallTimes)"

        if (hasAllPermissions()) {
            makePhoneCall()
        } else {
            requestPermissionLauncher.launch(requiredPermissions)
        }
    }

    private fun stopCallingSequence() {
        isCallingSequence = false
        handler.removeCallbacksAndMessages(null)
        // hang up right now
        try { endCurrentCall() } catch (_:Exception){}

        dialButton.text = "Dial Number"
        appendLog("Sequence stopped at $currentCallTimes/$totalCallTimes")
    }

    private fun onSequenceComplete() {
        isCallingSequence = false
        dialButton.text = "Dial Number"
        appendLog("Sequence complete: $currentCallTimes/$totalCallTimes done")
    }

    private fun makePhoneCall() {
        if (!isCallingSequence) return

        if (currentCallTimes >= totalCallTimes) {
            onSequenceComplete()
            return
        }

        currentCallTimes++
        appendLog("Call $currentCallTimes: Dialing $outgoingNumber")

        val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$outgoingNumber"))
        try {
            startActivity(callIntent)

            // hang up after the user-set interval, then next call
            handler.postDelayed({
                endCurrentCall()
                handler.postDelayed({ makePhoneCall() }, 5_000L)
            }, callIntervalSec * 1_000L)

        } catch (secEx: SecurityException) {
            appendLog("Call $currentCallTimes failed: ${secEx.message}")
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // hide your chrome in PiP if you like
        dialButton.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        callStatusLog.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
    }

    private fun endCurrentCall() {
        if (lastCallState != TelephonyManager.CALL_STATE_IDLE) {
            appendLog("Call $currentCallTimes end")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // reflection to invoke TelecomManager.endCall()
                    val end: Method = telecomManager.javaClass.getMethod("endCall")
                    end.invoke(telecomManager)
                } else {
                    // old fallback: MEDIA_BUTTON KEYCODE_ENDCALL
                    listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
                        val bcast = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                            putExtra(
                                Intent.EXTRA_KEY_EVENT,
                                KeyEvent(action, KeyEvent.KEYCODE_ENDCALL)
                            )
                        }
                        sendBroadcast(bcast)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                appendLog("Error ending call")
            }
        }
    }

    private fun updateDeviceNumber() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Make sure we have at least READ_PHONE_NUMBERS before calling into SubscriptionManager
                if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_PHONE_STATE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissionLauncher.launch(requiredPermissions)
                    return
                }
                val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val activeSubscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
                if (!activeSubscriptionInfoList.isNullOrEmpty()) {
                    val phoneNumber = subscriptionManager.getPhoneNumber(activeSubscriptionInfoList[0].subscriptionId)
                    deviceNumberEdit.setText(phoneNumber ?: "Unknown")
                } else {
                    deviceNumberEdit.setText("No active subscription")
                }
            } else {
                @Suppress("DEPRECATION")
                val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                deviceNumberEdit.setText(telephonyManager.line1Number ?: "Unknown")
            }
        } catch (e: SecurityException) {
            deviceNumberEdit.setText("Permission required")
        }
    }

    private fun makePhoneCallIfReady() {
        if (::outgoingNumberEdit.isInitialized && ::callTimesEdit.isInitialized) {
            outgoingNumber = outgoingNumberEdit.text.toString()
            totalCallTimes = callTimesEdit.text.toString().toIntOrNull() ?: 1
            currentCallTimes = 0
            makePhoneCall()
        }
    }

    private fun answerPhoneCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ANSWER_PHONE_CALLS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                telecomManager.acceptRingingCall()
            } else {
                // request ANSWER_PHONE_CALLS if missing
                requestPermissionLauncher.launch(requiredPermissions)
            }
        } else {
            @Suppress("DEPRECATION")
            telephonyManager
            .javaClass
            .getDeclaredMethod("answerRingingCall")
            .invoke(telephonyManager)
        }
    }
}
