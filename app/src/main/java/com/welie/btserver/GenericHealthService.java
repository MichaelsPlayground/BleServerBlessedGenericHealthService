package com.welie.btserver;

import static android.bluetooth.BluetoothGattCharacteristic.PERMISSION_READ;
import static android.bluetooth.BluetoothGattCharacteristic.PERMISSION_WRITE;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY;
import static android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ;

import static com.welie.blessed.BluetoothBytesParser.FORMAT_FLOAT;
import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT16;
import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT32;
import static com.welie.blessed.BluetoothBytesParser.FORMAT_UINT8;
import static com.welie.blessed.BluetoothBytesParser.bytes2String;
import static com.welie.blessed.BluetoothBytesParser.mergeArrays;

import static java.lang.Math.min;
import static java.nio.ByteOrder.LITTLE_ENDIAN;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.welie.blessed.BluetoothBytesParser;
import com.welie.blessed.BluetoothCentral;
import com.welie.blessed.BluetoothPeripheralManager;
import com.welie.blessed.GattStatus;
import com.welie.blessed.ReadResponse;

import org.jetbrains.annotations.NotNull;

import java.util.Calendar;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import timber.log.Timber;

public class GenericHealthService extends BaseService {
    private static final UUID GHS_SERVICE_UUID = UUID.fromString("00007f44-0000-1000-8000-00805f9b34fb");
    private static final UUID OBSERVATION_CHAR_UUID = UUID.fromString("00007f43-0000-1000-8000-00805f9b34fb");
    private static final UUID GHS_FEATURES_CHAR_UUID = UUID.fromString("00007f41-0000-1000-8000-00805f9b34fb");
    private static final UUID GHS_SCHEDULE_CHANGED_CHAR_UUID = UUID.fromString("00007f3f-0000-1000-8000-00805f9b34fb");
    private static final UUID GHS_SCHEDULE_DESCRIPTOR_UUID = UUID.fromString("00007f35-0000-1000-8000-00805f9b34fb");

    private @NotNull
    final BluetoothGattService service = new BluetoothGattService(GHS_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

    private @NotNull
    final BluetoothGattCharacteristic scheduleChanged = new BluetoothGattCharacteristic(GHS_SCHEDULE_CHANGED_CHAR_UUID, PROPERTY_NOTIFY, 0);

    private @NotNull
    final BluetoothGattCharacteristic liveObservation = new BluetoothGattCharacteristic(OBSERVATION_CHAR_UUID, PROPERTY_NOTIFY, 0);

    private @NotNull
    final Handler handler = new Handler(Looper.getMainLooper());
    private final int MDC_PULS_OXIM_SAT_O2 = 150456;
    private final int MDC_DIM_PER_CENT = 0x0220;
    private final int NUMERIC_OBSERVATION = 0;
    private boolean isScheduleChangeCharNotifying = false;
    private volatile byte[] scheduleValue;
    private float interval = 1.0f;
    private final byte[] featureValue;
    private int measurement_duration = 1;
    private @NotNull
    final Runnable notifyRunnable = this::notifyLiveObservation;

    GenericHealthService(@NotNull BluetoothPeripheralManager peripheralManager) {
        super(peripheralManager);

        BluetoothBytesParser parser = new BluetoothBytesParser();
        parser.setIntValue(MDC_PULS_OXIM_SAT_O2, FORMAT_UINT32);
        parser.setFloatValue(1.0f, 1);
        parser.setFloatValue(interval, 1);
        scheduleValue = parser.getValue().clone();

        BluetoothGattCharacteristic feature = new BluetoothGattCharacteristic(GHS_FEATURES_CHAR_UUID, PROPERTY_READ, PERMISSION_READ);
        BluetoothGattDescriptor scheduleDescriptor = new BluetoothGattDescriptor(GHS_SCHEDULE_DESCRIPTOR_UUID, PERMISSION_READ | PERMISSION_WRITE);
        feature.addDescriptor(scheduleDescriptor);

        BluetoothBytesParser parser2 = new BluetoothBytesParser();
        parser2.setIntValue(0, FORMAT_UINT8);
        parser2.setIntValue(1, FORMAT_UINT8);
        parser2.setIntValue(MDC_PULS_OXIM_SAT_O2, FORMAT_UINT32);
        featureValue = parser2.getValue().clone();
        service.addCharacteristic(feature);

        scheduleChanged.addDescriptor(getCccDescriptor());
        service.addCharacteristic(scheduleChanged);

        liveObservation.addDescriptor(getCccDescriptor());
        service.addCharacteristic(liveObservation);
    }

    @Override
    public ReadResponse onCharacteristicRead(@NotNull BluetoothCentral central, @NotNull BluetoothGattCharacteristic characteristic) {
        if (characteristic.getUuid()==GHS_FEATURES_CHAR_UUID) {
            return new ReadResponse(GattStatus.SUCCESS, featureValue);
        }
        return new ReadResponse(GattStatus.REQUEST_NOT_SUPPORTED, null);
    }

    private void notifyLiveObservation() {
        int minMTU = getMinMTU();

        byte[] observation = createObservation(96.1f);
        byte[] packet;
        if (minMTU - 4 >= observation.length ) {
            packet = mergeArrays(new byte[] {0x03}, observation);
            Timber.d("notifying observation <%s>", bytes2String(observation));
            notifyCharacteristicChanged(packet, liveObservation);
        } else {
            int numberOfSegments = (int) Math.ceil((double) observation.length / (minMTU - 4));
            int observationIndex = 0;
            int observationRemaining = observation.length;
            for (int i = 0 ; i<numberOfSegments; i++) {
                int segmentsize = min(minMTU-4, observationRemaining);
                byte[] segment = new byte[segmentsize];
                System.arraycopy(observation, observationIndex, segment, 0, segmentsize);
                observationRemaining -= segmentsize;
                observationIndex += segmentsize;

                if (i==0) {
                    packet = mergeArrays(new byte[] {0x01}, segment);
                } else if (i==numberOfSegments - 1) {
                    packet = mergeArrays(new byte[] {(byte) ((i << 2) + 2)}, segment);
                } else {
                    packet = mergeArrays(new byte[] {(byte) (i << 2)}, segment);
                }
                Timber.d("notifying observation <%s>", bytes2String(packet));
                notifyCharacteristicChanged(packet, liveObservation);
            }
        }
        handler.postDelayed(notifyRunnable, (long) (interval * 1000L));
    }

    private void addElapsedTime(@NotNull BluetoothBytesParser parser) {
        long elapsed_time_epoch = 946684800;
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeZone(TimeZone.getTimeZone("UTC"));
        long seconds_since_unix_epoch = calendar.getTime().getTime() / 1000;
        long seconds_since_ets_epoch = seconds_since_unix_epoch - elapsed_time_epoch;

        parser.setIntValue(0x22, FORMAT_UINT8);  // Flags
        parser.setIntValue((int) (seconds_since_ets_epoch & 0xFF), FORMAT_UINT8);
        parser.setIntValue((int) ((seconds_since_ets_epoch >> 8) & 0xFF), FORMAT_UINT8);
        parser.setIntValue((int) ((seconds_since_ets_epoch >> 16) & 0xFF), FORMAT_UINT8);
        parser.setIntValue((int) ((seconds_since_ets_epoch >> 24) & 0xFF), FORMAT_UINT8);
        parser.setIntValue((int) ((seconds_since_ets_epoch >> 32) & 0xFF), FORMAT_UINT8);
        parser.setIntValue((int) ((seconds_since_ets_epoch >> 40) & 0xFF), FORMAT_UINT8);
        parser.setIntValue(0x06, FORMAT_UINT8);  // Cellular Network
        parser.setIntValue(0x00, FORMAT_UINT8);  // Tz/DST offset
    }

    private byte[] createObservation(float spo2Value) {
        BluetoothBytesParser parser = new BluetoothBytesParser();
        parser.setIntValue(NUMERIC_OBSERVATION, BluetoothGattCharacteristic.FORMAT_UINT8);
        parser.setIntValue(25, FORMAT_UINT16);  // Length
        parser.setIntValue(0x07, FORMAT_UINT16);  // Flags
        parser.setIntValue(MDC_PULS_OXIM_SAT_O2, FORMAT_UINT32);
        addElapsedTime(parser);
        parser.setFloatValue(measurement_duration, 1);
        parser.setIntValue(MDC_DIM_PER_CENT, FORMAT_UINT16);
        parser.setFloatValue(spo2Value, 1);
        return parser.getValue().clone();
    }

    @Override
    public void onNotifyingEnabled(@NotNull BluetoothCentral central, @NotNull BluetoothGattCharacteristic characteristic) {
        if (characteristic.getUuid().equals(GHS_SCHEDULE_CHANGED_CHAR_UUID)) {
            isScheduleChangeCharNotifying = true;
        } else if (characteristic.getUuid().equals(OBSERVATION_CHAR_UUID)) {
            notifyLiveObservation();
        }
    }

    @Override
    public void onNotifyingDisabled(@NotNull BluetoothCentral central, @NotNull BluetoothGattCharacteristic characteristic) {
        if (characteristic.getUuid().equals(GHS_SCHEDULE_CHANGED_CHAR_UUID)) {
            isScheduleChangeCharNotifying = false;
        } else if (characteristic.getUuid().equals(OBSERVATION_CHAR_UUID)) {
            handler.removeCallbacks(notifyRunnable);
        }
    }

    @Override
    public void onDescriptorWriteCompleted(@NotNull BluetoothCentral central, @NotNull BluetoothGattDescriptor descriptor, @NonNull byte[] value) {
        if (isScheduleChangeCharNotifying) {
            Set<BluetoothCentral> allCentrals = peripheralManager.getConnectedCentrals();
            for (BluetoothCentral connectedCentral: allCentrals) {
                if (!(connectedCentral.getAddress().equals(central.getAddress()))) {
                    peripheralManager.notifyCharacteristicChanged(value, connectedCentral, scheduleChanged);
                }
            }
        }
    }

    @Override
    public GattStatus onDescriptorWrite(@NotNull BluetoothCentral central, @NotNull BluetoothGattDescriptor descriptor, byte[] value) {
        if (value.length != 12) return GattStatus.VALUE_OUT_OF_RANGE;

        BluetoothBytesParser parser = new BluetoothBytesParser(value, 0, LITTLE_ENDIAN);
        final int mdc = parser.getIntValue(FORMAT_UINT32);
        final float schedule_measurement_period = parser.getFloatValue(FORMAT_FLOAT);
        final float schedule_update_interval = parser.getFloatValue(FORMAT_FLOAT);

        if (mdc != MDC_PULS_OXIM_SAT_O2) {
            return GattStatus.VALUE_OUT_OF_RANGE;
        }

        if (schedule_measurement_period > 5 || schedule_measurement_period < 1) {
            return GattStatus.VALUE_OUT_OF_RANGE;
        }

        if (schedule_update_interval < schedule_measurement_period || schedule_update_interval > 10) {
            return GattStatus.VALUE_OUT_OF_RANGE;
        }

        scheduleValue = value;
        interval = schedule_update_interval;
        return GattStatus.SUCCESS;
    }

    @Override
    public ReadResponse onDescriptorRead(@NotNull BluetoothCentral central, @NotNull BluetoothGattDescriptor descriptor) {
        BluetoothGattCharacteristic characteristic = Objects.requireNonNull(descriptor.getCharacteristic(), "Descriptor has no Characteristic");
        if (characteristic.getUuid().equals(GHS_FEATURES_CHAR_UUID) && descriptor.getUuid().equals(GHS_SCHEDULE_DESCRIPTOR_UUID)) {
            Timber.d("returning <%s> for schedule descriptor", bytes2String(scheduleValue));
            return new ReadResponse(GattStatus.SUCCESS, scheduleValue);
        }
        return new ReadResponse(GattStatus.REQUEST_NOT_SUPPORTED, null);
    }

    @Override
    public @NotNull BluetoothGattService getService() {
        return service;
    }

    @Override
    public String getServiceName() {
        return "Generic Health Service";
    }

    private int getMinMTU() {
        Set<BluetoothCentral> allCentrals = peripheralManager.getConnectedCentrals();
        return allCentrals.stream().map(BluetoothCentral::getCurrentMtu).min(Integer::compare).get();
    }
}
