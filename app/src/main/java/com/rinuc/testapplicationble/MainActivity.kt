package com.rinuc.testapplicationble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.text.format.DateFormat
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.google.android.material.snackbar.Snackbar
import com.rinuc.testapplicationble.databinding.ActivityMainBinding
import com.rinuc.testapplicationble.gatt.TimeProfile
import java.util.Arrays
import java.util.Date
import java.util.UUID


private const val TAG = "GattServerActivity"
private const val REQUEST_ALL_PERMISSION = 2

class MainActivity : AppCompatActivity() {

  private lateinit var appBarConfiguration: AppBarConfiguration
  private lateinit var binding: ActivityMainBinding

  private lateinit var bluetoothManager: BluetoothManager
  private var bluetoothGattServer: BluetoothGattServer? = null
  private val registeredDevices = mutableSetOf<BluetoothDevice>()

  private var sharedPreferences: SharedPreferences? = null
  private var editor: SharedPreferences.Editor? = null

  private var deviceID: String = ""

  val PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.BLUETOOTH_ADVERTISE,
  )

  private fun hasPermissions(context: Context?, permissions: Array<String>): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context != null) {
      for (permission in permissions) {
        if (ActivityCompat.checkSelfPermission(context, permission)
          != PackageManager.PERMISSION_GRANTED) {
          return false
        }
      }
    }
    return true
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)

    when (requestCode) {
      REQUEST_ALL_PERMISSION -> {
        // If request is cancelled, the result arrays are empty.
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
          Toast.makeText(this, "Permissions granted!", Toast.LENGTH_SHORT).show()
        } else {
          requestPermissions(permissions, REQUEST_ALL_PERMISSION)
          Toast.makeText(this, "Permissions must be granted", Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  @SuppressLint("MissingPermission")
  private fun notifyRegisteredDevices(timestamp: Long, adjustReason: Byte) {
    if (registeredDevices.isEmpty()) {
      Log.i(TAG, "No subscribers registered")
      return
    }
    val exactTime = TimeProfile.getExactTime(timestamp, adjustReason)

    Log.i(TAG, "Sending update to ${registeredDevices.size} subscribers")
    for (device in registeredDevices) {
      val timeCharacteristic = bluetoothGattServer
        ?.getService(TimeProfile.TIME_SERVICE)
        ?.getCharacteristic(TimeProfile.CURRENT_TIME)
      timeCharacteristic?.value = exactTime
      bluetoothGattServer?.notifyCharacteristicChanged(device, timeCharacteristic, false)
    }
  }

  private fun updateLocalUi(timestamp: Long) {
    val date = Date(timestamp)
    val displayDate = DateFormat.getMediumDateFormat(this).format(date)
    val displayTime = DateFormat.getTimeFormat(this).format(date)
    Log.i(TAG, "$displayDate\n$displayTime")
  }

  private val timeReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val adjustReason = when (intent.action) {
        Intent.ACTION_TIME_CHANGED -> TimeProfile.ADJUST_MANUAL
        Intent.ACTION_TIMEZONE_CHANGED -> TimeProfile.ADJUST_TIMEZONE
        Intent.ACTION_TIME_TICK -> TimeProfile.ADJUST_NONE
        else -> TimeProfile.ADJUST_NONE
      }
      val now = System.currentTimeMillis()
      notifyRegisteredDevices(now, adjustReason)
      updateLocalUi(now)
    }
  }

  @SuppressLint("MissingPermission")
  private fun startAdvertising() {
    val tmp = deviceID.split("-")
    val device = tmp.get(tmp.size - 1)
    Log.d(TAG, device)
    bluetoothManager.adapter.setName(device)
    val bluetoothLeAdvertiser: BluetoothLeAdvertiser? =
      bluetoothManager.adapter.bluetoothLeAdvertiser

    bluetoothLeAdvertiser?.let {
      val settings = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
        .setConnectable(true)
        .setTimeout(0)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
        .build()

      val data = AdvertiseData.Builder()
        .setIncludeDeviceName(true)
        .setIncludeTxPowerLevel(false)
        .addServiceUuid(ParcelUuid(TimeProfile.TIME_SERVICE))
        .build()

      it.startAdvertising(settings, data, advertiseCallback)
    } ?: Log.w(TAG, "Failed to create advertiser")
  }

  @SuppressLint("MissingPermission")
  private fun stopAdvertising() {
    val bluetoothLeAdvertiser: BluetoothLeAdvertiser? =
      bluetoothManager.adapter.bluetoothLeAdvertiser
    bluetoothLeAdvertiser?.let {
      it.stopAdvertising(advertiseCallback)
    } ?: Log.w(TAG, "Failed to create advertiser")
  }

  @SuppressLint("MissingPermission")
  private fun startServer() {
    bluetoothGattServer = bluetoothManager.openGattServer(this, gattServerCallback)

    bluetoothGattServer?.addService(TimeProfile.createTimeService())
      ?: Log.w(TAG, "Unable to create GATT server")

    // Initialize the local UI
    updateLocalUi(System.currentTimeMillis())
  }

  @SuppressLint("MissingPermission")
  private fun stopServer() {
    bluetoothGattServer?.close()
  }

  private fun checkBluetoothSupport(bluetoothAdapter: BluetoothAdapter?): Boolean {

    if (bluetoothAdapter == null) {
      Log.w(TAG, "Bluetooth is not supported")
      return false
    }

    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
      Log.w(TAG, "Bluetooth LE is not supported")
      return false
    }

    return true
  }

  private val bluetoothReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)

      when (state) {
        BluetoothAdapter.STATE_ON -> {
          startAdvertising()
          startServer()
        }
        BluetoothAdapter.STATE_OFF -> {
          stopServer()
          stopAdvertising()
        }
      }
    }
  }

  private val advertiseCallback = object : AdvertiseCallback() {
    override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
      Log.i(TAG, "LE Advertise Started.")
    }

    override fun onStartFailure(errorCode: Int) {
      Log.w(TAG, "LE Advertise Failed: $errorCode")
    }
  }

  private val gattServerCallback = object : BluetoothGattServerCallback() {

    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
      if (newState == BluetoothProfile.STATE_CONNECTED) {
        Log.i(TAG, "BluetoothDevice CONNECTED: $device")
      } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        Log.i(TAG, "BluetoothDevice DISCONNECTED: $device")
        //Remove device from any active subscriptions
        registeredDevices.remove(device)
      }
    }

    @SuppressLint("MissingPermission")
    override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                             characteristic: BluetoothGattCharacteristic) {
      val now = System.currentTimeMillis()
      when {
        TimeProfile.CURRENT_TIME == characteristic.uuid -> {
          Log.i(TAG, "Read CurrentTime")
          bluetoothGattServer?.sendResponse(device,
            requestId,
            BluetoothGatt.GATT_SUCCESS,
            0,
            TimeProfile.getExactTime(now, TimeProfile.ADJUST_NONE))
        }
        TimeProfile.LOCAL_TIME_INFO == characteristic.uuid -> {
          Log.i(TAG, "Read LocalTimeInfo")
          bluetoothGattServer?.sendResponse(device,
            requestId,
            BluetoothGatt.GATT_SUCCESS,
            0,
            TimeProfile.getLocalTimeInfo(now))
        }
        else -> {
          // Invalid characteristic
          Log.w(TAG, "Invalid Characteristic Read: " + characteristic.uuid)
          bluetoothGattServer?.sendResponse(device,
            requestId,
            BluetoothGatt.GATT_FAILURE,
            0,
            null)
        }
      }
    }

    @SuppressLint("MissingPermission")
    override fun onCharacteristicWriteRequest(
      device: BluetoothDevice?,
      requestId: Int,
      characteristic: BluetoothGattCharacteristic?,
      preparedWrite: Boolean,
      responseNeeded: Boolean,
      offset: Int,
      value: ByteArray?
    ) {
//      super.onCharacteristicWriteRequest(
//        device,
//        requestId,
//        characteristic,
//        preparedWrite,
//        responseNeeded,
//        offset,
//        value
//      )
//      Log.i(TAG, "============! ======! =============!")
      Thread.sleep(200)
      if (responseNeeded) {
        bluetoothGattServer?.sendResponse(device,
          requestId,
          BluetoothGatt.GATT_SUCCESS,
          0, null)
      }

    }

    @SuppressLint("MissingPermission")
    override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                         descriptor: BluetoothGattDescriptor) {
      if (TimeProfile.CLIENT_CONFIG == descriptor.uuid) {
        Log.d(TAG, "Config descriptor read")
        val returnValue = if (registeredDevices.contains(device)) {
          BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
          BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }
        bluetoothGattServer?.sendResponse(device,
          requestId,
          BluetoothGatt.GATT_SUCCESS,
          0,
          returnValue)
      } else {
        Log.w(TAG, "Unknown descriptor read request")
        bluetoothGattServer?.sendResponse(device,
          requestId,
          BluetoothGatt.GATT_FAILURE,
          0, null)
      }
    }

    @SuppressLint("MissingPermission")
    override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int,
                                          descriptor: BluetoothGattDescriptor,
                                          preparedWrite: Boolean, responseNeeded: Boolean,
                                          offset: Int, value: ByteArray) {
      if (TimeProfile.CLIENT_CONFIG == descriptor.uuid) {
        if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
          Log.d(TAG, "Subscribe device to notifications: $device")
          registeredDevices.add(device)
        } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
          Log.d(TAG, "Unsubscribe device from notifications: $device")
          registeredDevices.remove(device)
        }

        if (responseNeeded) {
          Log.d(TAG, "============! ======! =============!")
          bluetoothGattServer?.sendResponse(device,
            requestId,
            BluetoothGatt.GATT_SUCCESS,
            0, null)
        }
      } else {
        Log.w(TAG, "Unknown descriptor write request")
        if (responseNeeded) {
          bluetoothGattServer?.sendResponse(device,
            requestId,
            BluetoothGatt.GATT_FAILURE,
            0, null)
        }
      }
    }
  }

  @SuppressLint("MissingPermission")
  override fun onCreate(savedInstanceState: Bundle?) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    super.onCreate(savedInstanceState)

    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    setSupportActionBar(binding.toolbar)

    if (!hasPermissions(this, PERMISSIONS)) {
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        requestPermissions(PERMISSIONS, REQUEST_ALL_PERMISSION)
      }
    }

    sharedPreferences = getApplicationContext().getSharedPreferences("testble", 0);
    editor = sharedPreferences?.edit()

    deviceID = sharedPreferences?.getString("deviceID", "").toString()
    Log.d(TAG, "before: ")
    Log.d(TAG, deviceID)
    if (deviceID.equals("")) {
      deviceID = UUID.randomUUID().toString()
      editor?.putString("deviceID", deviceID)
      editor?.apply()
    }
    Log.d(TAG, "current : ")
    Log.d(TAG, deviceID)

    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter = bluetoothManager.adapter
    // We can't continue without proper Bluetooth support
    if (!checkBluetoothSupport(bluetoothAdapter)) {
      finish()
    }

    val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
    registerReceiver(bluetoothReceiver, filter)
    if (!bluetoothAdapter.isEnabled) {
      Log.d(TAG, "Bluetooth is currently disabled...enabling")
      bluetoothAdapter.enable()
    } else {
      Log.d(TAG, "Bluetooth enabled...starting services")
      startAdvertising()
      startServer()
    }

    val navController = findNavController(R.id.nav_host_fragment_content_main)
    appBarConfiguration = AppBarConfiguration(navController.graph)
    setupActionBarWithNavController(navController, appBarConfiguration)

    binding.fab.setOnClickListener { view ->
      Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
        .setAnchorView(R.id.fab)
        .setAction("Action", null).show()
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    // Inflate the menu; this adds items to the action bar if it is present.
    menuInflater.inflate(R.menu.menu_main, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    // Handle action bar item clicks here. The action bar will
    // automatically handle clicks on the Home/Up button, so long
    // as you specify a parent activity in AndroidManifest.xml.
    return when (item.itemId) {
      R.id.action_settings -> true
      else -> super.onOptionsItemSelected(item)
    }
  }

  override fun onSupportNavigateUp(): Boolean {
    val navController = findNavController(R.id.nav_host_fragment_content_main)
    return navController.navigateUp(appBarConfiguration)
        || super.onSupportNavigateUp()
  }

  override fun onStart() {
    super.onStart()

    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_TIME_TICK)
      addAction(Intent.ACTION_TIME_CHANGED)
      addAction(Intent.ACTION_TIMEZONE_CHANGED)
    }

    registerReceiver(timeReceiver, filter)
  }

  override fun onStop() {
    super.onStop()
    unregisterReceiver(timeReceiver)
  }

  override fun onDestroy() {
    super.onDestroy()

    val bluetoothAdapter = bluetoothManager.adapter
    if (bluetoothAdapter.isEnabled) {
      stopServer()
      stopAdvertising()
    }

    unregisterReceiver(bluetoothReceiver)
  }
}