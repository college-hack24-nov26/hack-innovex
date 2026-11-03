package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.AlertItem
import com.example.model.Direction
import com.example.model.Obstacle
import com.example.model.ObstacleSeverity
import com.example.model.UserSettings
import com.example.reader.InfoReader
import com.example.settings.UserSettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("AuraSight", appName)
    }

    @Test
    fun `verify obstacle danger spoken alert`() {
        val obstacle = Obstacle(
            label = "Wall",
            distanceMeters = 0.8f,
            direction = Direction.CENTER,
            severity = ObstacleSeverity.DANGER
        )

        val alertText = obstacle.toSpokenAlert()
        assertTrue(alertText.contains("Stop! Wall directly ahead, 0.8 meters."))
        assertEquals(ObstacleSeverity.DANGER, obstacle.severity)
    }

    @Test
    fun `verify obstacle warning spoken alert`() {
        val obstacle = Obstacle(
            label = "Person",
            distanceMeters = 2.4f,
            direction = Direction.CENTER,
            severity = ObstacleSeverity.WARNING
        )

        val alertText = obstacle.toSpokenAlert()
        assertTrue(alertText.contains("Caution, Person 2.4 meters ahead."))
    }

    @Test
    fun `verify alert item priority ordering`() {
        val dangerAlert = AlertItem(
            priority = AlertItem.PRIORITY_DANGER,
            message = "Danger! Wall ahead"
        )
        val navAlert = AlertItem(
            priority = AlertItem.PRIORITY_NAVIGATION,
            message = "Turn left in 30 meters"
        )
        val readerAlert = AlertItem(
            priority = AlertItem.PRIORITY_READER,
            message = "500 Rupees."
        )

        assertTrue(dangerAlert.priority < navAlert.priority)
        assertTrue(navAlert.priority < readerAlert.priority)
        assertTrue(dangerAlert.interrupts)
    }

    @Test
    fun `verify indian pan card recognition`() {
        val reader = InfoReader()
        val ocrSample = """
            INCOME TAX DEPARTMENT
            GOVT. OF INDIA
            Permanent Account Number Card
            ABCDE1234F
            RAJESH SHARMA
            RAMESH SHARMA
            15/08/1985
        """.trimIndent()

        val result = reader.evaluateDocument(ocrSample)
        assertNotNull(result)
        assertEquals("Indian PAN Card", result?.title)
        assertTrue(result?.spokenAnnouncement?.contains("PAN Card") == true)
        assertTrue(result?.spokenAnnouncement?.contains("ABCDE1234F") == true || result?.spokenAnnouncement?.contains("A B C D E") == true)
    }

    @Test
    fun `verify indian aadhaar card recognition`() {
        val reader = InfoReader()
        val ocrSample = """
            Government of India
            Unique Identification Authority of India
            Enrollment No: 1234/56789/01234
            Priya Sharma
            9876 5432 1098
            Mera Aadhaar, Meri Pehchan
        """.trimIndent()

        val result = reader.evaluateDocument(ocrSample)
        assertNotNull(result)
        assertEquals("Indian Aadhaar Card", result?.title)
        assertTrue(result?.spokenAnnouncement?.contains("Aadhaar Card") == true)
        assertTrue(result?.spokenAnnouncement?.contains("1098") == true)
    }

    @Test
    fun `verify indian currency note recognition`() {
        val reader = InfoReader()
        val ocrSample500 = "RESERVE BANK OF INDIA 500 FIVE HUNDRED RUPEES"
        val result500 = reader.evaluateCurrency(ocrSample500, null)

        assertNotNull(result500)
        assertEquals("₹500 Note", result500?.title)
        assertEquals("500 Rupees.", result500?.spokenAnnouncement)
    }

    @Test
    fun `verify bus number and destination recognition`() {
        val reader = InfoReader()
        val busBoardText = "BMTC BUS ROUTE 335-E MAJESTIC TO ITPL"
        val result = reader.evaluateBusNumber(busBoardText)

        assertNotNull(result)
        assertEquals("Bus 335-E to Majestic.", result?.spokenAnnouncement)
    }

    @Test
    fun `verify user settings persistence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = UserSettingsManager(context)

        val testSettings = UserSettings(
            userName = "Rajesh Sharma",
            emergencyContactName = "Priya Sharma",
            emergencyContactPhone = "+919876543210",
            caregiverEmail = "priya@example.com",
            homeAddress = "B-42, Shanti Nagar, New Delhi",
            workAddress = "Tech Park, MG Road, Bengaluru"
        )

        manager.saveSettings(testSettings)
        val loaded = manager.loadSettings()

        assertEquals("Rajesh Sharma", loaded.userName)
        assertEquals("+919876543210", loaded.emergencyContactPhone)
        assertEquals("B-42, Shanti Nagar, New Delhi", loaded.homeAddress)
        assertTrue(loaded.isHomeSet)
        assertTrue(loaded.isEmergencyContactSet)
    }

    @Test
    fun `verify plain wall danger alert formatting`() {
        val wallObstacle = Obstacle(
            label = "Wall",
            distanceMeters = 1.0f,
            direction = Direction.CENTER,
            severity = ObstacleSeverity.DANGER
        )
        val alert = wallObstacle.toSpokenAlert()
        assertTrue(alert.contains("Stop! Wall directly ahead, 1.0 meters."))
    }

    @Test
    fun `verify general document recognition`() {
        val reader = InfoReader()
        val electricityBill = """
            MAHARASHTRA STATE ELECTRICITY DISTRIBUTION CO.
            ELECTRICITY BILL
            Consumer No: 02854910284
            Due Date: 25/11/2026
            Amount: 1450
        """.trimIndent()

        val result = reader.evaluateDocument(electricityBill)
        assertNotNull(result)
        assertTrue(result?.spokenAnnouncement?.contains("Document:") == true)
    }
}
