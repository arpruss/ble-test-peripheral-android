/*
 * Copyright 2015 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.webbluetoothcg.bletestperipheral;

import android.app.Activity;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

public class AccelServiceFragment extends ServiceFragment implements SensorEventListener {
  private static final String TAG = AccelServiceFragment.class.getCanonicalName();
  private static final int MIN_UINT = 0;
  private static final int MAX_UINT8 = (int) Math.pow(2, 8) - 1;
  private static final int MAX_UINT16 = (int) Math.pow(2, 16) - 1;
  /**
   * See <a href="https://developer.bluetooth.org/gatt/services/Pages/ServiceViewer.aspx?u=org.bluetooth.service.ACCELEROMETER.xml">
   * Heart Rate Service</a>
   */
  private static final UUID ACCELEROMETER_SERVICE_UUID = UUID
      .fromString("b9590f4e-f0c4-46cc-8c4f-096fec764f91");

  private static final UUID GRAVITY_MEASUREMENT_UUID = UUID
      .fromString("a0e83db5-08eb-44c2-a493-5e5f3dfce286");
  private static final int GRAVITY_MEASUREMENT_VALUE_FORMAT = BluetoothGattCharacteristic.FORMAT_SFLOAT;
  private static final String GRAVITY_MEASUREMENT_DESCRIPTION = "Used to send gravity " +
      "measurement";

  private BluetoothGattService mAccelerometerService;
  private BluetoothGattCharacteristic mGravityMeasurementCharacteristic;

  private ServiceFragmentDelegate mDelegate;

  private Sensor sensor;
  private SensorManager sensorManager;

  public AccelServiceFragment() {
    mGravityMeasurementCharacteristic =
        new BluetoothGattCharacteristic(GRAVITY_MEASUREMENT_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            /* No permissions */ 0);

    mGravityMeasurementCharacteristic.addDescriptor(
        Peripheral.getClientCharacteristicConfigurationDescriptor());

    mGravityMeasurementCharacteristic.addDescriptor(
        Peripheral.getCharacteristicUserDescriptionDescriptor(GRAVITY_MEASUREMENT_DESCRIPTION));

    mAccelerometerService = new BluetoothGattService(ACCELEROMETER_SERVICE_UUID,
        BluetoothGattService.SERVICE_TYPE_PRIMARY);
    mAccelerometerService.addCharacteristic(mGravityMeasurementCharacteristic);
  }


  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container,
      Bundle savedInstanceState) {

    View view = inflater.inflate(R.layout.fragment_accel, container, false);

    sensorManager = (SensorManager) getActivity().getSystemService(Context.SENSOR_SERVICE);
    return view;
  }

  @Override
  public void onResume() {
    super.onResume();
    sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
    sensorManager.registerListener((SensorEventListener) this, sensor, SensorManager.SENSOR_DELAY_GAME);
    getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  @Override
  public void onPause() {
    super.onPause();
    sensorManager.unregisterListener(this);
    getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  @Override
  public void onAttach(Activity activity) {
    super.onAttach(activity);
    try {
      mDelegate = (ServiceFragmentDelegate) activity;
    } catch (ClassCastException e) {
      throw new ClassCastException(activity.toString()
          + " must implement ServiceFragmentDelegate");
    }
  }

  @Override
  public void onDetach() {
    super.onDetach();
    mDelegate = null;
  }

  @Override
  public BluetoothGattService getBluetoothGattService() {
    return mAccelerometerService;
  }

  @Override
  public ParcelUuid getServiceUUID() {
    return new ParcelUuid(ACCELEROMETER_SERVICE_UUID);
  }

  private void setAccelerometerMeasurementValue(float x, float y, float z) {
    /* Set the org.bluetooth.characteristic.ACCELEROMETER_measurement
     * characteristic to a byte array of size 4 so
     * we can use setValue(value, format, offset);
     *
     * Flags (8bit) + Heart Rate Measurement Value (uint8) + Energy Expended (uint16) = 4 bytes
     *
     * Flags = 1 << 3:
     *   Heart Rate Format (0) -> UINT8
     *   Sensor Contact Status (00) -> Not Supported
     *   Energy Expended (1) -> Field Present
     *   RR-Interval (0) -> Field not pressent
     *   Unused (000)
     */
    ByteBuffer out = ByteBuffer.allocate(12);
    out.order(ByteOrder.LITTLE_ENDIAN);
    out.putFloat(x);
    out.putFloat(y);
    out.putFloat(z);
    mGravityMeasurementCharacteristic.setValue(out.array());
    mDelegate.sendNotificationToDevices(mGravityMeasurementCharacteristic);
    }

  private boolean isValidCharacteristicValue(String s, int format) {
    try {
      int value = Integer.parseInt(s);
      if (format == BluetoothGattCharacteristic.FORMAT_UINT8) {
        return (value >= MIN_UINT) && (value <= MAX_UINT8);
      } else if (format == BluetoothGattCharacteristic.FORMAT_UINT16) {
        return (value >= MIN_UINT) && (value <= MAX_UINT16);
      } else {
        throw new IllegalArgumentException(format + " is not a valid argument");
      }
    } catch (NumberFormatException e) {
      return false;
    }
  }

  @Override
  public int writeCharacteristic(BluetoothGattCharacteristic characteristic, int offset, byte[] value) {
    if (offset != 0) {
      return BluetoothGatt.GATT_INVALID_OFFSET;
    }
    // Heart Rate control point is a 8bit characteristic
    if (value.length != 1) {
      return BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH;
    }
    if ((value[0] & 1) == 1) {
      getActivity().runOnUiThread(new Runnable() {
        @Override
        public void run() {
        }
      });
    }
    return BluetoothGatt.GATT_SUCCESS;
  }

  @Override
  public void notificationsEnabled(BluetoothGattCharacteristic characteristic, boolean indicate) {
    if (characteristic.getUuid() != GRAVITY_MEASUREMENT_UUID) {
      return;
    }
    if (indicate) {
      return;
    }
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        Toast.makeText(getActivity(), R.string.notificationsEnabled, Toast.LENGTH_SHORT)
            .show();
      }
    });
  }

  @Override
  public void notificationsDisabled(BluetoothGattCharacteristic characteristic) {
    if (characteristic.getUuid() != GRAVITY_MEASUREMENT_UUID) {
      return;
    }
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        Toast.makeText(getActivity(), R.string.notificationsNotEnabled, Toast.LENGTH_SHORT)
            .show();
      }
    });
  }

  @Override
  public void onSensorChanged(SensorEvent sensorEvent) {
    setAccelerometerMeasurementValue(sensorEvent.values[0],sensorEvent.values[1],sensorEvent.values[2]);
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int i) {

  }
}
