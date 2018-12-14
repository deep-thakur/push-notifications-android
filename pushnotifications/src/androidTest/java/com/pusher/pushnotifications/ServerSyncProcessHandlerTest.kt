package com.pusher.pushnotifications

import android.support.test.InstrumentationRegistry
import android.support.test.runner.AndroidJUnit4
import com.pusher.pushnotifications.api.PushNotificationsAPI
import com.pusher.pushnotifications.internal.*
import junit.framework.Assert.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.forPath
import okhttp3.mockwebserver.enqueue
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.CoreMatchers.equalTo
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ServerSyncProcessHandlerTest {
  @Before
  @After
  fun cleanup() {
    val deviceStateStore = DeviceStateStore(InstrumentationRegistry.getTargetContext())
    Assert.assertTrue(deviceStateStore.clear())
    Assert.assertNull(deviceStateStore.deviceId)
    Assert.assertThat(deviceStateStore.interests.size, `is`(equalTo(0)))
  }

  val instanceId = "000000-c1de-09b9-a8f6-2a22dbdd062a"
  val mockServer = MockWebServer().apply { start() }
  val api = PushNotificationsAPI(instanceId, mockServer.url("/").toString())
  val deviceStateStore = DeviceStateStore(InstrumentationRegistry.getTargetContext())
  val jobQueue = {
    val tempFile = File.createTempFile("persistentJobQueue-", ".queue")
    tempFile.delete() // QueueFile expects a handle to a non-existent file on first run.
    TapeJobQueue<ServerSyncJob>(tempFile)
  }()
  val handler = ServerSyncProcessHandler(api, deviceStateStore, jobQueue, InstrumentationRegistry.getContext().mainLooper)

  /**
   * Non-start jobs should be skipped if the SDK has not been started yet.
   */
  @Test
  fun skipJobsBeforeStart() {
    listOf(
        ServerSyncHandler.subscribe("interest-0"),
        ServerSyncHandler.subscribe("interest-1"),
        ServerSyncHandler.unsubscribe("interest-0"),
        ServerSyncHandler.setSubscriptions(setOf("interest-0", "interest-2"))
    ).forEach { job ->
      jobQueue.push(job.obj as ServerSyncJob)
      handler.handleMessage(job)
    }

    assertThat(mockServer.requestCount, `is`(equalTo(0)))
  }

  @Test
  fun doPendingJobsAfterStart() {
    val startJob = ServerSyncHandler.start("token-123", emptyList())
    listOf(
        ServerSyncHandler.subscribe("interest-0"),
        ServerSyncHandler.subscribe("interest-1"),
        ServerSyncHandler.unsubscribe("interest-0"),
        ServerSyncHandler.setSubscriptions(setOf("interest-0", "interest-2"))
    ).forEach { job ->
      jobQueue.push(job.obj as ServerSyncJob)
      handler.handleMessage(job)
    }

    assertThat(mockServer.requestCount, `is`(equalTo(0)))

    // expect register device
    mockServer.enqueue(MockResponse().forPath("/instance").setBody("""{"id": "d-123", "initialInterestSet": []}"""))
    // expect set interests
    mockServer.enqueue(MockResponse().setBody(""))

    jobQueue.push(startJob.obj as ServerSyncJob)
    handler.handleMessage(startJob)

    assertThat(mockServer.requestCount, `is`(equalTo(2)))
    assertTrue(jobQueue.peek() == null)
  }

  @Test
  fun doJobsAfterStart() {
    val startJob = ServerSyncHandler.start("token-123", emptyList())
    jobQueue.push(startJob.obj as ServerSyncJob)

    // expect register device
    mockServer.enqueue(MockResponse().setBody("""{"id": "d-123", "initialInterestSet": []}"""))

    handler.handleMessage(startJob)

    assertThat(mockServer.requestCount, `is`(equalTo(1)))

    listOf(
        ServerSyncHandler.subscribe("interest-0"),
        ServerSyncHandler.subscribe("interest-1"),
        ServerSyncHandler.unsubscribe("interest-0"),
        ServerSyncHandler.setSubscriptions(setOf("interest-0", "interest-2"))
    ).withIndex().forEach { (index, job) ->
      assertThat(mockServer.requestCount, `is`(equalTo(1 + index)))
      jobQueue.push(job.obj as ServerSyncJob)
      mockServer.enqueue(MockResponse().setBody(""))
      handler.handleMessage(job)
      assertThat(mockServer.requestCount, `is`(equalTo(1 + index + 1)))
    }

    assertTrue(jobQueue.peek() == null)
  }

  @Test
  fun startAndThenRefreshToken() {
    val startJob = ServerSyncHandler.start("token-123", emptyList())
    jobQueue.push(startJob.obj as ServerSyncJob)

    // expect register device
    mockServer.enqueue(MockResponse().setBody("""{"id": "d-123", "initialInterestSet": []}"""))

    handler.handleMessage(startJob)

    assertThat(mockServer.requestCount, `is`(equalTo(1)))

    val refreshTokenJob = ServerSyncHandler.refreshToken("new-token")
    jobQueue.push(refreshTokenJob.obj as ServerSyncJob)
    mockServer.enqueue(MockResponse().setBody(""))
    handler.handleMessage(refreshTokenJob)
    assertThat(mockServer.requestCount, `is`(equalTo(2)))

    assertTrue(jobQueue.peek() == null)
  }

  @Test
  fun startDoSomeOperationsWhileHandlingUnexpectedDeviceDeletionCorrectly() {
    val startJob = ServerSyncHandler.start("token-123", emptyList())
    jobQueue.push(startJob.obj as ServerSyncJob)

    // expect register device
    mockServer.enqueue(MockResponse().setBody("""{"id": "d-123", "initialInterestSet": []}"""))

    handler.handleMessage(startJob)

    assertThat(mockServer.requestCount, `is`(equalTo(1)))

    // expect to fail with 404 not found the next subscribe
    mockServer.enqueue(MockResponse().setResponseCode(404).setBody(""))

    // expect register device
    mockServer.enqueue(MockResponse().setBody("""{"id": "d-123", "initialInterestSet": []}"""))
    // expect a subscribe
    mockServer.enqueue(MockResponse().setBody(""))

    val subJob = ServerSyncHandler.subscribe("hello")
    jobQueue.push(subJob.obj as ServerSyncJob)
    handler.handleMessage(subJob)

    assertThat(mockServer.requestCount, `is`(equalTo(4)))

    assertTrue(jobQueue.peek() == null)
  }

  @Test
  fun skipJobsIf400BadRequestAreReceived() {
    val startJob = ServerSyncHandler.start("token-123", emptyList())
    jobQueue.push(startJob.obj as ServerSyncJob)

    // expect register device
    mockServer.enqueue(MockResponse().setBody("""{"id": "d-123", "initialInterestSet": []}"""))

    handler.handleMessage(startJob)

    assertThat(mockServer.requestCount, `is`(equalTo(1)))

    // expect to fail with 400 for the next subscribe
    mockServer.enqueue(MockResponse().setResponseCode(400).setBody(""))

    // expect an unsubscribe
    mockServer.enqueue(MockResponse().setBody(""))

    val subJob = ServerSyncHandler.subscribe("hello")
    jobQueue.push(subJob.obj as ServerSyncJob)
    handler.handleMessage(subJob)

    val unsubJob = ServerSyncHandler.unsubscribe("hello")
    jobQueue.push(unsubJob.obj as ServerSyncJob)
    handler.handleMessage(unsubJob)

    assertThat(mockServer.requestCount, `is`(equalTo(3)))

    assertTrue(jobQueue.peek() == null)
  }
}