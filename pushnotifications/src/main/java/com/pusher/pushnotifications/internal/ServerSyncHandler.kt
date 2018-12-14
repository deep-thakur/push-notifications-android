package com.pusher.pushnotifications.internal

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import com.pusher.pushnotifications.SubscriptionsChangedListener
import com.pusher.pushnotifications.api.PushNotificationsAPI
import com.pusher.pushnotifications.api.PushNotificationsAPIBadRequest
import com.pusher.pushnotifications.api.PushNotificationsAPIDeviceNotFound
import com.pusher.pushnotifications.api.RetryStrategy
import com.pusher.pushnotifications.logging.Logger
import java.io.Serializable

sealed class ServerSyncJob: Serializable
data class StartJob(val fcmToken: String, val knownPreviousClientIds: List<String>): ServerSyncJob()
data class RefreshTokenJob(val newToken: String): ServerSyncJob()
data class SubscribeJob(val interest: String): ServerSyncJob()
data class UnsubscribeJob(val interest: String): ServerSyncJob()
data class SetSubscriptionsJob(val interests: Set<String>): ServerSyncJob()

class ServerSyncHandler(
    private val api: PushNotificationsAPI,
    private val deviceStateStore: DeviceStateStore,
    private val jobQueue: PersistentJobQueue<ServerSyncJob>,
    looper: Looper
): Handler(looper) {
  private val serverSyncProcessHandler = {
    val handlerThread = HandlerThread(looper.thread.name + "-inner-worker")
    handlerThread.start()

    ServerSyncProcessHandler(api, deviceStateStore, jobQueue, handlerThread.looper)
  }()

  init {
    // when the app first launches, we should queue up all of the outstanding
    // jobs in the queue so we can pick up where we have left off
    jobQueue.asIterable().forEach { job ->
      serverSyncProcessHandler.sendMessage(Message().also { it.obj = job })
    }
  }

  fun setOnSubscriptionsChangedListener(onSubscriptionsChangedListener: SubscriptionsChangedListener) {
    serverSyncProcessHandler.onSubscriptionsChangedListener = onSubscriptionsChangedListener
  }

  override fun handleMessage(msg: Message) {
    super.handleMessage(msg)
    val job = msg.obj as ServerSyncJob
    jobQueue.push(job)

    val clonedMsg = Message()
    clonedMsg.obj = msg.obj
    serverSyncProcessHandler.sendMessage(clonedMsg)
  }

  companion object {
  fun refreshToken(fcmToken: String): Message =
      Message().also {
        it.obj = RefreshTokenJob(fcmToken)
      }

  fun start(fcmToken: String, knownPreviousClientIds: List<String>): Message =
      Message().also {
        it.obj = StartJob(fcmToken, knownPreviousClientIds)
      }

  fun subscribe(interest: String): Message =
      Message().also {
        it.obj = SubscribeJob(interest)
      }

  fun unsubscribe(interest: String): Message =
      Message().also {
        it.obj = UnsubscribeJob(interest)
      }

  fun setSubscriptions(interests: Set<String>): Message =
      Message().also {
        it.obj = SetSubscriptionsJob(interests)
      }
  }
}

class ServerSyncProcessHandler(
    private val api: PushNotificationsAPI,
    private val deviceStateStore: DeviceStateStore,
    private val jobQueue: PersistentJobQueue<ServerSyncJob>,
    looper: Looper
): Handler(looper) {
  private val log = Logger.get(this::class)
  private val started: Boolean
  get() = deviceStateStore.deviceId != null

  internal var onSubscriptionsChangedListener: SubscriptionsChangedListener? = null

  private fun recreateDevice(fcmToken: String) {
    // Register device with Errol
    val registrationResponse =
        api.registerFCM(
            token = fcmToken,
            knownPreviousClientIds = emptyList(),
            retryStrategy = RetryStrategy.WithInfiniteExpBackOff())

    var localInterests: Set<String> = emptySet()
    synchronized(deviceStateStore) {
      deviceStateStore.deviceId = registrationResponse.deviceId
      deviceStateStore.FCMToken = fcmToken

      localInterests = deviceStateStore.interests
    }

    if (localInterests.count() > 0) {
      api.setSubscriptions(
          deviceId = registrationResponse.deviceId,
          interests = deviceStateStore.interests,
          retryStrategy = RetryStrategy.WithInfiniteExpBackOff())
    }
  }

  private fun processStartJob(startJob: StartJob) {
    // Register device with Errol
    val registrationResponse =
        api.registerFCM(
            token = startJob.fcmToken,
            knownPreviousClientIds = startJob.knownPreviousClientIds,
            retryStrategy = RetryStrategy.WithInfiniteExpBackOff())

    synchronized(deviceStateStore) {
      deviceStateStore.deviceId = registrationResponse.deviceId
      deviceStateStore.FCMToken = startJob.fcmToken

      // Replay sub/unsub/setsub operations in job queue over initial interest set
      val interests = registrationResponse.initialInterests.toMutableSet()
      for(j in jobQueue.asIterable()) {
        if (j is StartJob) {
          break
        }
        when(j) {
          is SubscribeJob -> {
            interests += j.interest
          }
          is UnsubscribeJob -> {
            interests -= j.interest
          }
          is SetSubscriptionsJob -> {
            interests.clear()
            interests.addAll(j.interests)
          }
        }
      }

      val localInterestWillChange = deviceStateStore.interests != interests

      // Replace interests with the result
      if (localInterestWillChange) {
        deviceStateStore.interests = interests
        onSubscriptionsChangedListener?.let { listener ->
          // always using the UI thread
          Handler(Looper.getMainLooper()).post {
            listener.onSubscriptionsChanged(interests.toSet())
          }
        }
      }
    }

    val remoteInterestsWillChange = deviceStateStore.interests != registrationResponse.initialInterests
    if (remoteInterestsWillChange) {
      api.setSubscriptions(
          deviceId = registrationResponse.deviceId,
          interests = deviceStateStore.interests,
          retryStrategy = RetryStrategy.WithInfiniteExpBackOff())
    }

    // Clear queue up to the start job
    while (jobQueue.peek() !is StartJob) {
      jobQueue.pop()
    }
    jobQueue.pop() // Also remove start job
  }

  private fun processJob(job: ServerSyncJob) {
    try {
      when(job) {
        is SubscribeJob -> {
          api.subscribe(
              deviceStateStore.deviceId!!,
              job.interest,
              RetryStrategy.WithInfiniteExpBackOff())
        }
        is UnsubscribeJob -> {
          api.unsubscribe(
              deviceStateStore.deviceId!!,
              job.interest,
              RetryStrategy.WithInfiniteExpBackOff())
        }
        is SetSubscriptionsJob -> {
          api.setSubscriptions(
              deviceStateStore.deviceId!!,
              job.interests,
              RetryStrategy.WithInfiniteExpBackOff())
        }
        is RefreshTokenJob -> {
          api.refreshToken(
              deviceStateStore.deviceId!!,
              job.newToken,
              RetryStrategy.WithInfiniteExpBackOff())
        }
      }
    } catch (e: PushNotificationsAPIBadRequest) {
      // not really recoverable, so log it here and also monitor 400s closely on our backend
      // (this really shouldn't happen)
      log.e("Fail to make a valid request to the server for job ($job), skipping it", e)
    } catch (e: PushNotificationsAPIDeviceNotFound) {
      // server has forgotten about this device, it needs to be recreated
      recreateDevice(deviceStateStore.FCMToken!!)
      processJob(job)
      return // prevent the additional `jobQueue.pop`
    }
    jobQueue.pop()
  }

  override fun handleMessage(msg: Message) {
    val job = msg.obj as ServerSyncJob

    // If the SDK hasn't started yet we can't do anything, so skip
    val shouldSkip = !started && job !is StartJob
    if (shouldSkip) {
      return
    }

    if(job is StartJob) {
      processStartJob(job)
    } else {
      processJob(job)
    }
  }
}