package com.example.nebosvod

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException

enum class DeviceState {
    CONNECTED,      // Зеленый
    WAITING,        // Желтый
    ERROR,          // Красный
    DISCONNECTED    // Серый
}

class MainActivity : AppCompatActivity() {

    // --- Переменные состояния ---
    private var serialPort: UsbSerialPort? = null
    private var readThread: Thread? = null

    // --- Элементы UI ---
    private lateinit var statusOutput: TextView
    private lateinit var comOutput: TextView
    private lateinit var comScrollView: ScrollView
    private lateinit var terminalInput: EditText
    private lateinit var customAltInput: EditText

    // --- BroadcastReceiver для отслеживания USB ---
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    updateStatus("Подключение...", DeviceState.WAITING)
                    connectToArduino()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    updateStatus("Отключено", DeviceState.DISCONNECTED)
                    disconnectArduino()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        initViews()
        setupClickListeners()
        setupWindowInsets()

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, filter)

        updateStatus("Ожидание устройства...", DeviceState.WAITING)
        connectToArduino()
    }

    // ==========================================
    // ИНИЦИАЛИЗАЦИЯ UI И СЛУШАТЕЛЕЙ
    // ==========================================

    private fun initViews() {
        statusOutput = findViewById(R.id.output)
        comOutput = findViewById(R.id.comOutput)
        comScrollView = findViewById(R.id.comScrollView)
        terminalInput = findViewById(R.id.heightInput)
        customAltInput = findViewById(R.id.customAltInput)
    }

    private fun setupClickListeners() {
        // Кнопка логов
        findViewById<ImageButton>(R.id.logsButton).setOnClickListener {
            sendCommand("GET", "Запрос сохраненных высот")
        }

        // Ручная отправка из терминала
        findViewById<ImageButton>(R.id.sendButton).setOnClickListener {
            val text = terminalInput.text.toString().trim()
            if (text.isNotEmpty()) {
                sendCommand(text, "Ручная команда")
                terminalInput.text.clear()
            } else {
                Toast.makeText(this, "Введите команду", Toast.LENGTH_SHORT).show()
            }
            hideKeyboard(it)
            terminalInput.clearFocus()
        }

        // --- ТОЧНЫЕ КОМАНДЫ ДЛЯ ARDUINO С ОПИСАНИЯМИ ---
        findViewById<Button>(R.id.btnAlt).setOnClickListener {
            sendCommand("ALT", "Запрос текущей высоты")
        }
        findViewById<Button>(R.id.btnGet).setOnClickListener {
            sendCommand("GET", "Чтение памяти слотов")
        }
        findViewById<Button>(R.id.btnBuzzOn).setOnClickListener {
            sendCommand("1,4000", "Включение зуммера (4000 Гц)")
        }
        findViewById<Button>(R.id.btnBuzzOff).setOnClickListener {
            sendCommand("0", "Выключение зуммера")
        }

        // Кнопки записи в слоты (индексы от 0 до 3)
        setupSlotButton(R.id.btnSet0, 0)
        setupSlotButton(R.id.btnSet1, 1)
        setupSlotButton(R.id.btnSet2, 2)
        setupSlotButton(R.id.btnSet3, 3)
    }

    private fun setupSlotButton(buttonId: Int, slotIndex: Int) {
        findViewById<Button>(buttonId).setOnClickListener { view ->
            val altValue = customAltInput.text.toString().trim()
            if (altValue.isNotEmpty()) {
                sendCommand("SET,$slotIndex,$altValue", "Запись в Слот ${slotIndex + 1}: $altValue м")
                customAltInput.text.clear()
                hideKeyboard(view)
                customAltInput.clearFocus()
            } else {
                val warnMsg = "Введите высоту для записи!"
                Toast.makeText(this, warnMsg, Toast.LENGTH_SHORT).show()
                appendComData("\n! Ошибка: $warnMsg")
            }
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val insetsType = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            val padding = insets.getInsets(insetsType)
            v.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            insets
        }
    }

    // ==========================================
    // ЛОГИКА USB-СОЕДИНЕНИЯ
    // ==========================================

    private fun connectToArduino() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

        if (availableDrivers.isEmpty()) {
            updateStatus("Устройство не подключено", DeviceState.DISCONNECTED)
            return
        }

        val driver = availableDrivers[0]
        val connection = manager.openDevice(driver.device) ?: run {
            updateStatus("Нет прав на USB", DeviceState.ERROR)
            return
        }

        serialPort = driver.ports[0]

        try {
            serialPort?.open(connection)
            serialPort?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            serialPort?.dtr = true
            serialPort?.rts = true

            updateStatus("Подключено", DeviceState.CONNECTED)
            appendComData("\n--- Порт открыт (115200) ---")

            startReadingThread()

        } catch (e: IOException) {
            e.printStackTrace()
            updateStatus("Ошибка при открытии порта", DeviceState.ERROR)
        }
    }

    private fun disconnectArduino() {
        readThread?.interrupt()
        readThread = null
        try {
            serialPort?.close()
            appendComData("\n--- Порт закрыт ---")
        } catch (e: IOException) {
            e.printStackTrace()
        }
        serialPort = null
    }

    private fun startReadingThread() {
        readThread = Thread {
            val messageBuffer = StringBuilder()
            val buffer = ByteArray(1024)

            while (!Thread.currentThread().isInterrupted) {
                try {
                    val numBytesRead = serialPort?.read(buffer, 1000) ?: 0
                    if (numBytesRead > 0) {
                        messageBuffer.append(String(buffer, 0, numBytesRead))

                        var newlineIndex = messageBuffer.indexOf("\n")
                        while (newlineIndex != -1) {
                            val completeMessage = messageBuffer.substring(0, newlineIndex).trim()
                            messageBuffer.delete(0, newlineIndex + 1)

                            if (completeMessage.isNotEmpty()) {
                                appendComData("<- $completeMessage")
                            }
                            newlineIndex = messageBuffer.indexOf("\n")
                        }
                    }
                } catch (e: IOException) {
                    break
                }
            }
        }
        readThread?.start()
    }

    // ==========================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ОТПРАВКИ И UI
    // ==========================================

    // Обновленный метод отправки с поддержкой Тостов и логов
    private fun sendCommand(command: String, actionName: String? = null) {
        if (serialPort == null) {
            val errorMsg = "Ошибка: Устройство не подключено"
            updateStatus(errorMsg, DeviceState.ERROR)
            Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
            appendComData("\n! $errorMsg")
            return
        }

        val formattedCommand = if (command.endsWith(";")) command else "$command;"

        try {
            // Выводим Toast и понятную надпись в терминал, если передано название действия
            if (actionName != null) {
                Toast.makeText(this, actionName, Toast.LENGTH_SHORT).show()
                appendComData("\n▶ $actionName")
            }

            serialPort?.write("$formattedCommand\n".toByteArray(), 1000)
            appendComData("-> $formattedCommand")

        } catch (e: IOException) {
            e.printStackTrace()
            updateStatus("Ошибка отправки", DeviceState.ERROR)
            Toast.makeText(this, "Ошибка отправки команды", Toast.LENGTH_SHORT).show()
            appendComData("! Ошибка отправки: $formattedCommand")
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateStatus(message: String, state: DeviceState) {
        runOnUiThread {
            val colorHex = when (state) {
                DeviceState.CONNECTED -> "#4CAF50"
                DeviceState.WAITING -> "#FFB300"
                DeviceState.ERROR -> "#F44336"
                DeviceState.DISCONNECTED -> "#9E9E9E"
            }
            val color = Color.parseColor(colorHex)

            statusOutput.text = "Статус: $message"
            statusOutput.setTextColor(color)

            val scale = resources.displayMetrics.density
            val sizePx = (10 * scale + 0.5f).toInt()
            val paddingPx = (8 * scale + 0.5f).toInt()

            val dotDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setSize(sizePx, sizePx)
            }

            statusOutput.setCompoundDrawablesWithIntrinsicBounds(dotDrawable, null, null, null)
            statusOutput.compoundDrawablePadding = paddingPx
        }
    }

    private fun appendComData(data: String) {
        runOnUiThread {
            comOutput.append("$data\n")
            comScrollView.post { comScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        disconnectArduino()
    }
}