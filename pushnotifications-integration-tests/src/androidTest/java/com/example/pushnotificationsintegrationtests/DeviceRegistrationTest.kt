package com.example.pushnotificationsintegrationtests

import android.support.test.InstrumentationRegistry
import android.support.test.runner.AndroidJUnit4
import com.google.firebase.iid.FirebaseInstanceId
import com.pusher.pushnotifications.PushNotificationsInstance
import com.pusher.pushnotifications.SubscriptionsChangedListener
import com.pusher.pushnotifications.internal.DeviceStateStore
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.not
import org.junit.After

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*
import org.junit.Before

const val DEVICE_REGISTRATION_WAIT_MS: Long = 7000 // We need to wait for FCM to register the device etc.

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class DeviceRegistrationTest {
  fun getStoredDeviceId(): String? {
    val deviceStateStore = DeviceStateStore(InstrumentationRegistry.getTargetContext())
    return deviceStateStore.deviceId
  }

  val context = InstrumentationRegistry.getTargetContext()
  val instanceId = "00000000-1241-08e9-b379-377c32cd1e89"
  val errolClient = ErrolAPI(instanceId, "http://localhost:8080")
  companion object {
    val errol = FakeErrol(8080)
  }

  @Before
  @After
  fun cleanup() {
    val deviceStateStore = DeviceStateStore(InstrumentationRegistry.getTargetContext())
    assertTrue(deviceStateStore.clear())
    assertNull(deviceStateStore.deviceId)
    assertThat(deviceStateStore.interests.size, `is`(equalTo(0)))
  }

  @Test
  fun registerDeviceUponFreshStart() {
    // Start the SDK
    PushNotificationsInstance(context, instanceId).start()
    Thread.sleep(DEVICE_REGISTRATION_WAIT_MS)

    // A device ID should have been stored
    val storedDeviceId = getStoredDeviceId()
    assertNotNull(storedDeviceId)

    // and the stored id should match the server one
    val getDeviceResponse = errolClient.getDevice(storedDeviceId!!)
    assertNotNull(getDeviceResponse)
  }

  @Test
  fun subscribeToInterestsAfterStart() {
    // Start the SDK
    val pni = PushNotificationsInstance(context, instanceId).start()
    Thread.sleep(DEVICE_REGISTRATION_WAIT_MS)
    val storedDeviceId = getStoredDeviceId()
    assertNotNull(storedDeviceId)

    // The SDK should have no interests
    assertThat(pni.getSubscriptions(), `is`(emptySet()))

    // The server should have no interests for this device
    val interestsOnServer = errolClient.getDeviceInterests(storedDeviceId!!)
    assertThat(interestsOnServer, `is`(equalTo(pni.getSubscriptions())))

    // Subscribe to an interest
    pni.subscribe("peanuts")

    // The device should have that interest stored locally
    assertThat(pni.getSubscriptions(), `is`(equalTo(setOf("peanuts"))))

    // The server should have the interest too
    Thread.sleep(1000)
    val interestsOnServer2 = errolClient.getDeviceInterests(storedDeviceId!!)
    assertThat(interestsOnServer2, `is`(equalTo(pni.getSubscriptions())))
  }

  @Test
  fun subscribeToInterestsBeforeStartAndSyncAfterStart() {
    val pni = PushNotificationsInstance(context, instanceId)

    // The SDK should have no interests
    assertThat(pni.getSubscriptions(), `is`(emptySet()))

    // Subscribe to an interest
    pni.subscribe("peanuts")

    // The device should have that interest stored locally
    assertThat(pni.getSubscriptions(), `is`(equalTo(setOf("peanuts"))))

    // Start the SDK
    pni.start()
    Thread.sleep(DEVICE_REGISTRATION_WAIT_MS)
    val storedDeviceId = getStoredDeviceId()
    assertNotNull(storedDeviceId)

    // The server should have the interest too
    Thread.sleep(1000)
    val interestsOnServer2 = errolClient.getDeviceInterests(storedDeviceId!!)
    assertThat(interestsOnServer2, `is`(equalTo(pni.getSubscriptions())))
  }

  //@SkipIfProductionErrol
  @Test
  fun refreshTokenAfterStart() {
    // Start the SDK
    val pni = PushNotificationsInstance(context, instanceId).start()
    Thread.sleep(DEVICE_REGISTRATION_WAIT_MS)

    // A device ID should have been stored
    val storedDeviceId = getStoredDeviceId()
    assertNotNull(storedDeviceId)

    // and the stored id should match the server one
    val getDeviceResponse = errolClient.getDevice(storedDeviceId!!)
    assertNotNull(getDeviceResponse)

    val oldToken = errol.storage.devices[storedDeviceId]?.token
    assertThat(oldToken, `is`(not(equalTo(""))))

    FirebaseInstanceId.getInstance().deleteInstanceId()
    Thread.sleep(DEVICE_REGISTRATION_WAIT_MS)

    // The server should have the new token now
    val newToken = errol.storage.devices[storedDeviceId]?.token
    assertThat(newToken, `is`(not(equalTo(""))))
    assertThat(newToken, `is`(not(equalTo(oldToken))))
  }

  @Test
  fun startDoSomeOperationsWhileHandlingUnexpectedDeviceDeletionCorrectly() {
    // Start the SDK
    val pni = PushNotificationsInstance(context, instanceId)
    pni.subscribe("hello")
    pni.start()
    Thread.sleep(DEVICE_REGISTRATION_WAIT_MS)

    // A device ID should have been stored
    val storedDeviceId = getStoredDeviceId()
    assertNotNull(storedDeviceId)

    // and the stored id should match the server one
    val getDeviceResponse = errolClient.getDevice(storedDeviceId!!)
    assertNotNull(getDeviceResponse)

    errolClient.deleteDevice(storedDeviceId)

    pni.subscribe("potato")

    Thread.sleep(1000)

    val newStoredDeviceId = getStoredDeviceId()
    assertNotNull(newStoredDeviceId)
    assertThat(newStoredDeviceId, `is`(not(equalTo(storedDeviceId))))

    assertThat(pni.getSubscriptions(), `is`(equalTo(setOf("hello", "potato"))))
    val interestsOnServer = errolClient.getDeviceInterests(newStoredDeviceId!!)
    assertThat(interestsOnServer, `is`(equalTo(setOf("hello", "potato"))))
  }

  @Test
  fun OnSubscriptionsChangedListenerShouldBeCalledIfInterestsChange() {
    val pni = PushNotificationsInstance(context, instanceId)
    var setOnSubscriptionsChangedListenerCalledCount = 0
    var lastSetOnSubscriptionsChangedListenerCalledWithInterests: Set<String>? = null
    pni.setOnSubscriptionsChangedListener(object: SubscriptionsChangedListener {
      override fun onSubscriptionsChanged(interests: Set<String>) {
        setOnSubscriptionsChangedListenerCalledCount++
        lastSetOnSubscriptionsChangedListenerCalledWithInterests = interests
      }
    })
    
    pni.subscribe("hello")
    assertThat(setOnSubscriptionsChangedListenerCalledCount, `is`(equalTo(1)))
    assertThat(lastSetOnSubscriptionsChangedListenerCalledWithInterests, `is`(equalTo(setOf("hello"))))

    pni.subscribe("hello")
    assertThat(setOnSubscriptionsChangedListenerCalledCount, `is`(equalTo(1)))

    pni.unsubscribe("hello")
    assertThat(setOnSubscriptionsChangedListenerCalledCount, `is`(equalTo(2)))
    assertThat(lastSetOnSubscriptionsChangedListenerCalledWithInterests, `is`(equalTo(setOf())))

    pni.unsubscribe("hello")
    assertThat(setOnSubscriptionsChangedListenerCalledCount, `is`(equalTo(2)))

    pni.setSubscriptions(setOf("hello", "panda"))
    assertThat(setOnSubscriptionsChangedListenerCalledCount, `is`(equalTo(3)))
    assertThat(lastSetOnSubscriptionsChangedListenerCalledWithInterests, `is`(equalTo(setOf("hello", "panda"))))

    pni.setSubscriptions(setOf("hello", "panda"))
    assertThat(setOnSubscriptionsChangedListenerCalledCount, `is`(equalTo(3)))

    pni.unsubscribeAll()
    assertThat(setOnSubscriptionsChangedListenerCalledCount, `is`(equalTo(4)))
    assertThat(lastSetOnSubscriptionsChangedListenerCalledWithInterests, `is`(equalTo(setOf())))

    pni.unsubscribeAll()
    assertThat(setOnSubscriptionsChangedListenerCalledCount, `is`(equalTo(4)))
  }
}