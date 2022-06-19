package com.kieronquinn.app.ambientmusicmod.service

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.SensorPrivacyManager.Sensors
import android.hardware.SensorPrivacyManagerHidden
import android.hardware.SensorPrivacyManagerHidden.OnSensorPrivacyChangedListener
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaMetadata
import android.media.musicrecognition.MusicRecognitionManager
import android.media.musicrecognition.MusicRecognitionManager.RECOGNITION_FAILED_SERVICE_UNAVAILABLE
import android.media.musicrecognition.RecognitionRequest
import android.os.*
import android.view.IWindowManager
import androidx.core.os.BuildCompat
import com.android.internal.policy.IKeyguardDismissCallback
import com.android.internal.widget.ILockSettings
import com.kieronquinn.app.ambientmusicmod.*
import com.kieronquinn.app.ambientmusicmod.utils.extensions.*
import dev.rikka.tools.refine.Refine
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import rikka.shizuku.SystemServiceHelper
import java.util.*
import java.util.concurrent.Executors
import kotlin.system.exitProcess

@SuppressLint("WrongConstant")
class ShizukuService: IShellProxy.Stub() {

    companion object {
        private const val SHELL_UID = 2000
        private const val ROOT_UID = 0
        private const val SHELL_PACKAGE = "com.android.shell"
    }

    private val context by lazy {
        getActivityThreadContext()
    }

    private fun doesShellHavePermission(): Boolean {
        return context.packageManager.isPermissionGranted(
            SHELL_PACKAGE, "android.permission.CAPTURE_AUDIO_HOTWORD"
        )
    }

    private var _audioRecord: AudioRecord? = null
    private val audioRecord
        get() = _audioRecord ?: throw RuntimeException("Accessing an invalid AudioRecord")

    private val scope = MainScope()

    private val recordingLock = Object()
    private val musicRecognitionManagerExecutor = Executors.newSingleThreadExecutor()
    private val sensorPrivacyListeners = HashMap<String, OnSensorPrivacyChangedListener>()

    private val musicRecognitionManager by lazy {
        context.getSystemService("music_recognition") as MusicRecognitionManager
    }

    private val sensorPrivacyManager by lazy {
        Refine.unsafeCast<SensorPrivacyManagerHidden>(context.getSystemService("sensor_privacy"))
    }

    private val windowManager by lazy {
        val proxy = SystemServiceHelper.getSystemService("window")
        IWindowManager.Stub.asInterface(proxy)
    }

    private val lockSettings by lazy {
        val proxy = SystemServiceHelper.getSystemService("lock_settings")
        ILockSettings.Stub.asInterface(proxy)
    }

    private fun getUserHandle(): UserHandle {
        return Context::class.java.getMethod("getUser").invoke(context) as UserHandle
    }

    private fun getUserId(): Int {
        return UserHandle::class.java.getMethod("getIdentifier").invoke(getUserHandle()) as Int
    }

    /**
     *  Returns if Shell has CAPTURE_AUDIO_HOTWORD (in which case Shizuku as Shell is acceptable),
     *  or if the service is running as root, in which case it's compatible regardless.
     */
    override fun isCompatible(): Boolean {
        if(doesShellHavePermission()) return true
        return isRoot
    }

    override fun isRoot(): Boolean {
        return runWithClearedIdentity {
            Binder.getCallingUid() == 0
        }
    }

    override fun AudioRecord_create(
        attributes: AudioAttributes,
        audioFormat: AudioFormat,
        sessionId: Int,
        bufferSizeInBytes: Int
    ) {
        //The service can only handle one recording at once, so is locked while recording until release is called
        synchronized(recordingLock){
            _audioRecord = createAudioRecord(attributes, audioFormat, sessionId, bufferSizeInBytes)
        }
    }

    override fun AudioRecord_read(audioData: ByteArray, offsetInShorts: Int, sizeInShorts: Int): Int {
        val outShorts = ShortArray(sizeInShorts)
        return audioRecord.read(outShorts, offsetInShorts, sizeInShorts).also {
            outShorts.toByteArray().copyInto(audioData)
        }
    }

    override fun AudioRecord_startRecording() {
        audioRecord.startRecording()
    }

    override fun AudioRecord_release() {
        try {
            audioRecord.release()
            recordingLock.notify()
        }catch (e: IllegalMonitorStateException){
            //Already released
        }
    }

    override fun AudioRecord_getFormat(): AudioFormat {
        return audioRecord.format
    }

    override fun AudioRecord_getBufferSizeInFrames(): Int {
        return try {
            audioRecord.bufferSizeInFrames
        }catch (e: Exception){
            //Handle the occasional crash and return the default
            128000
        }
    }

    override fun AudioRecord_getSampleRate(): Int {
        return audioRecord.sampleRate
    }

    @SuppressLint("NewApi", "MissingPermission")
    private fun createAudioRecord(
        audioAttributes: AudioAttributes,
        audioFormat: AudioFormat,
        sessionId: Int,
        bufferSizeInBytes: Int
    ): AudioRecord {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> createAudioRecordApi31(
                audioAttributes, audioFormat, sessionId, bufferSizeInBytes
            )
            else -> createAudioRecordApi30(
                audioAttributes, audioFormat, sessionId, bufferSizeInBytes
            )
        }
    }

    @SuppressLint("NewApi", "MissingPermission")
    private fun createAudioRecordApi31(
        audioAttributes: AudioAttributes,
        audioFormat: AudioFormat,
        sessionId: Int,
        bufferSizeInBytes: Int
    ): AudioRecord {
        val shellContext = ShellContext(context)
        return AudioRecord::class.java.getDeclaredConstructor(
            AudioAttributes::class.java, //attributes
            AudioFormat::class.java, // format
            Integer.TYPE, // bufferSizeInBytes
            Integer.TYPE, // sessionId
            Context::class.java, // context
            Integer.TYPE // maxSharedAudioHistoryMs
        ).apply {
            isAccessible = true
        }.newInstance(
            audioAttributes, audioFormat, bufferSizeInBytes, sessionId, shellContext, 0
        )
    }

    @SuppressLint("NewApi", "MissingPermission")
    private fun createAudioRecordApi30(
        audioAttributes: AudioAttributes,
        audioFormat: AudioFormat,
        sessionId: Int,
        bufferSizeInBytes: Int
    ): AudioRecord = runWithClearedIdentity {
        replaceBaseContextIfRequired()
        AudioRecord::class.java.getDeclaredConstructor(
            AudioAttributes::class.java, //attributes
            AudioFormat::class.java, // format
            Integer.TYPE, // bufferSizeInBytes
            Integer.TYPE // sessionId
        ).apply {
            isAccessible = true
        }.newInstance(
            audioAttributes, audioFormat, bufferSizeInBytes, sessionId
        )
    }

    private fun replaceBaseContextIfRequired() {
        val base = getActivityThreadApplication().getBase()
        if(base !is ContextWrapper){
            getActivityThreadApplication().setBase(ShellContext(base))
        }
    }

    private inner class ShellContext(context: Context): ContextWrapper(context) {

        override fun getBaseContext(): Context {
            return super.getBaseContext()
        }

        override fun getOpPackageName(): String {
            return "uid:0"
        }

        @SuppressLint("NewApi")
        override fun getAttributionSource(): AttributionSource {
            val uid = if(isRoot) ROOT_UID else SHELL_UID
            return AttributionSource.Builder(uid)
                .setPackageName(SHELL_PACKAGE).build()
        }
    }

    override fun MusicRecognitionManager_beginStreamingSearch(
        request: RecognitionRequest,
        callback: IRecognitionCallback
    ) = runWithClearedIdentity {
        try {
            val systemCallback = object : MusicRecognitionManager.RecognitionCallback {
                override fun onRecognitionSucceeded(
                    recognitionRequest: RecognitionRequest,
                    result: MediaMetadata,
                    extras: Bundle?
                ) {
                    callback.onRecognitionSucceeded(recognitionRequest, result, extras)
                }

                override fun onRecognitionFailed(
                    recognitionRequest: RecognitionRequest,
                    failureCode: Int
                ) {
                    callback.onRecognitionFailed(recognitionRequest, failureCode)
                }

                override fun onAudioStreamClosed() {
                    callback.onAudioStreamClosed()
                }
            }
            musicRecognitionManager.beginStreamingSearch(
                request,
                musicRecognitionManagerExecutor,
                systemCallback
            )
        }catch (e: Exception){
            callback.onRecognitionFailed(request, RECOGNITION_FAILED_SERVICE_UNAVAILABLE)
        }
        Unit
    }

    override fun ping(): Boolean {
        return true
    }

    override fun addMicrophoneDisabledListener(callback: IMicrophoneDisabledStateCallback): String? {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runWithClearedIdentity {
            val id = UUID.randomUUID().toString()
            val systemCallback = OnSensorPrivacyChangedListener { sensor: Int, enabled: Boolean ->
                if (sensor == Sensors.MICROPHONE) {
                    callback.onMicrophoneDisabledStateChanged(enabled)
                }
            }
            sensorPrivacyListeners[id] = systemCallback
            sensorPrivacyManager.addSensorPrivacyListener(Sensors.MICROPHONE, systemCallback)
            id
        }
    }

    override fun removeMicrophoneDisabledListener(id: String) {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runWithClearedIdentity {
            sensorPrivacyListeners.remove(id)?.let {
                sensorPrivacyManager.removeSensorPrivacyListener(Sensors.MICROPHONE, it)
            }
        }

    }

    override fun isMicrophoneDisabled(): Boolean {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runWithClearedIdentity {
            (sensorPrivacyManager.isSensorPrivacyEnabled(Sensors.MICROPHONE))
        }
    }

    override fun destroy() {
        scope.cancel()
        exitProcess(0)
    }

    override fun getSystemUIPackageName(): String {
        return context.packageManager.getSystemUI()
    }

    override fun dismissKeyguard(callback: IBinder, message: String?) {
        val dismissCallback = IKeyguardDismissCallback.Stub.asInterface(callback)
        windowManager.dismissKeyguard(dismissCallback, message)
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun grantAccessibilityPermission() {
        if(!BuildCompat.isAtLeastT()) return
        //Grant the Restricted Settings AppOps permission, required to enable accessibility on A13
        runCommand("appops set ${BuildConfig.APPLICATION_ID} ACCESS_RESTRICTED_SETTINGS allow")
    }

    override fun setOwnerInfo(info: String) = runWithClearedIdentity {
        if(!isRoot){
            return@runWithClearedIdentity
        } //Can't run without root
        val userId = getUserId()
        lockSettings.setBoolean("lock_screen_owner_info_enabled", true, userId)
        lockSettings.setString("lock_screen_owner_info", info, userId)
        //The Settings values are no longer used but this triggers a content update
        runCommand("settings put secure lock_screen_owner_info_enabled true")
        runCommand("settings put secure lock_screen_owner_info \"$info\"")
    }

    override fun forceStopNowPlaying() {
        runCommand("am force-stop $PACKAGE_NAME_PAM")
    }

    private fun runCommand(command: String){
        Runtime.getRuntime().exec(command)
    }

    private fun <T> runWithClearedIdentity(block: () -> T): T {
        val token = Binder.clearCallingIdentity()
        return block().also {
            Binder.restoreCallingIdentity(token)
        }
    }

}