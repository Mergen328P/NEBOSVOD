package com.example.nebosvod

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException

class MainActivity : AppCompatActivity() {

    // Variables to hold the serial port and reading thread
    private var serialPort: UsbSerialPort? = null
    private var readThread: Thread? = null

    // Custom device name variable
    private val deviceName = "Nebosvod"

    // BroadcastReceiver to detect when the device is plugged or unplugged
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    updateStatus("Обнаружено USB устройство. Подключение...")
                    connectToArduino()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    updateStatus("Устройство отключено")
                    disconnectArduino()
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Find views once
        val sendButton: Button = findViewById(R.id.sendButton)
        val logsButton: ImageButton = findViewById(R.id.logs)
        val input: EditText = findViewById(R.id.heightInput)

        // Register the USB receiver to listen for plug/unplug events
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, filter)

        // Initialize the connection on startup
        connectToArduino()

        // Set up "Send" button click listener
        sendButton.setOnClickListener {
            if (serialPort == null) {
                updateStatus("Ошибка: Нет подключения к $deviceName")
                return@setOnClickListener
            }

            val inputText = buildString {
                append(input.text.toString())
                append(";")
            }

            // Send the text to the connected device
            writeToArduino(inputText)

            // Hide the keyboard and clear focus from the EditText
            hideKeyboard(it)
            input.clearFocus()
        }

        // Set up "Logs" button click listener
        logsButton.setOnClickListener {
            if (serialPort == null) {
                updateStatus("Ошибка: Нет подключения к $deviceName")
                return@setOnClickListener
            }

            val inputText = "4;"

            // Send the text to the connected device
            writeToArduino(inputText)
        }

        // Apply window insets listener to handle system bars AND keyboard (IME) pushing UI up
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v: View, insets: WindowInsetsCompat ->
            val insetsType = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            val padding = insets.getInsets(insetsType)

            v.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            insets
        }
    }

    private fun connectToArduino() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

        if (availableDrivers.isEmpty()) {
            updateStatus("Устройство не найдено. Подключите $deviceName.")
            return
        }

        val driver = availableDrivers[0]
        val connection = manager.openDevice(driver.device)

        if (connection == null) {
            // This usually happens if the user hasn't granted USB permission yet
            updateStatus("Нет прав на USB или устройство занято")
            return
        }

        serialPort = driver.ports[0]

        try {
            serialPort?.open(connection)

            // Note: Set to 115200 here. Make sure Arduino's Serial.begin() matches!
            serialPort?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            // Essential for Nano/Pro Micro to start sending data back
            serialPort?.dtr = true
            serialPort?.rts = true

            updateStatus("Успешно подключено!")

            // Start reading thread with an accumulator to prevent text fragmentation
            readThread = Thread {
                val messageBuffer = java.lang.StringBuilder()

                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val buffer = ByteArray(1024)
                        val numBytesRead = serialPort?.read(buffer, 1000) ?: 0

                        if (numBytesRead > 0) {
                            val receivedData = String(buffer, 0, numBytesRead)
                            messageBuffer.append(receivedData)

                            var newlineIndex = messageBuffer.indexOf("\n")

                            while (newlineIndex != -1) {
                                val completeMessage = messageBuffer.substring(0, newlineIndex).trim()
                                messageBuffer.delete(0, newlineIndex + 1)

                                // Update UI with the FULL message, cropped to 50 chars if necessary
                                updateStatus(
                                    if (completeMessage.length > 50) "${completeMessage.take(50)}..."
                                    else completeMessage
                                )

                                newlineIndex = messageBuffer.indexOf("\n")
                            }
                        }
                    } catch (e: IOException) {
                        break
                    }
                }
            }
            readThread?.start()

        } catch (e: IOException) {
            e.printStackTrace()
            updateStatus("Ошибка при открытии порта")
        }
    }

    private fun disconnectArduino() {
        readThread?.interrupt()
        readThread = null
        try {
            serialPort?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        serialPort = null
    }

    private fun writeToArduino(data: String) {
        val bytes = "$data\n".toByteArray()
        try {
            serialPort?.write(bytes, 1000)
        } catch (e: IOException) {
            e.printStackTrace()
            updateStatus("Ошибка отправки данных")
        }
    }

    // Helper function to dynamically update the output TextView from any thread
    private fun updateStatus(message: String) {
        runOnUiThread {
            val output: TextView = findViewById(R.id.output)
            output.text = message
        }
    }

    // Helper function to hide the software keyboard
    private fun hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister the receiver to prevent memory leaks and close connections safely
        unregisterReceiver(usbReceiver)
        disconnectArduino()
    }
}