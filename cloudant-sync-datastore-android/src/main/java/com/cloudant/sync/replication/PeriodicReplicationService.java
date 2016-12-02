/*
 * Copyright © 2016 IBM Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.cloudant.sync.replication;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

/**
 * This {@link android.app.Service} is an abstract class that is the basis for creating a service
 * that performs periodic replications (i.e. replications that occur at regular intervals). The
 * period between replications may be varied depending on whether other application components
 * are bound to the service or not, so as to allow for more frequent replications when an app
 * is in active use and less frequent replications the rest of the time.
 *
 * @param <T> The {@link PeriodicReplicationReceiver} associated with this Service that is
 *           responsible for handling the alarms triggered by the {@link AlarmManager} at
 *           the intervals when replication is required and handles resetting of alarms after
 *           reboot of the device.
 *
 * @api_public
 */
public abstract class PeriodicReplicationService<T extends PeriodicReplicationReceiver>
    extends ReplicationService {

    /* Name of the SharedPreferences file used to store alarm times. We store the alarm
     * times in preferences so we can reset the alarms as accurately as possible after reboot
     * and so we can adjust alarm times when components bind to or unbind from this Service. */
    private static final String PREFERENCES_FILE_NAME = "com.cloudant.preferences";

    /* We store the elapsed time since booting at which the next alarm is due in SharedPreferences
     * using this key. This is used to adjust alarm times when components bind to or unbind from
     * this Service. */
    private static final String PREFERENCE_LAST_ALARM_ELAPSED_TIME_SUFFIX = ".lastAlarmElapsed";

    /* We store the wall-clock time at which the next alarm is due in SharedPreferences
     * using this key. This is used to set the initial alarm after a reboot. */
    private static final String PREFERENCE_LAST_ALARM_CLOCK_TIME_SUFFIX = ".lastAlarmClock";

    /* We store a flag indicating whether periodic replications are enabled in SharedPreferences
     * using this key. We have to store the flag persistently as the service may be stopped and
     * started by the operating system. */
    private static final String PREFERENCE_PERIODIC_REPLICATION_ENABLED_SUFFIX
        = ".periodicReplicationsActive";

    private static final long MILLISECONDS_IN_SECOND = 1000L;

    public static final int COMMAND_START_PERIODIC_REPLICATION = 2;
    public static final int COMMAND_STOP_PERIODIC_REPLICATION = 3;
    public static final int COMMAND_DEVICE_REBOOTED = 4;
    public static final int COMMAND_RESET_REPLICATION_TIMERS = 5;

    private static final String TAG = "PRS";

    private SharedPreferences mPrefs;
    Class<T> clazz;
    protected boolean mBound;

    protected PeriodicReplicationService(Class<T> clazz) {
        this.clazz = clazz;
    }

    /**
     * If the stored preferences are in the old format, upgrade them to the new format so that
     * the app continues to work after upgrade to this version.
     */
    private void upgradePreferences() {
        String alarmDueElapsed = "com.cloudant.sync.replication.PeriodicReplicationService.alarmDueElapsed";
        if (mPrefs.contains(alarmDueElapsed)) {
            // These are old style preferences. We need to rewrite them in the new form that allows
            // multiple replication policies.
            String alarmDueClock = "com.cloudant.sync.replication.PeriodicReplicationService.alarmDueClock";
            String replicationsActive = "com.cloudant.sync.replication.PeriodicReplicationService.periodicReplicationsActive";
            long elapsed = mPrefs.getLong(alarmDueElapsed, 0);
            long clock = mPrefs.getLong(alarmDueClock, 0);
            boolean enabled = mPrefs.getBoolean(replicationsActive, false);

            SharedPreferences.Editor editor = mPrefs.edit();
            String className = getClass().getName();
            editor.putLong(className + PREFERENCE_LAST_ALARM_ELAPSED_TIME_SUFFIX,
                elapsed - (getIntervalInSeconds() * MILLISECONDS_IN_SECOND));
            editor.putLong(className + PREFERENCE_LAST_ALARM_CLOCK_TIME_SUFFIX,
                clock - (getIntervalInSeconds() * MILLISECONDS_IN_SECOND));
            editor.putBoolean(className + PREFERENCE_PERIODIC_REPLICATION_ENABLED_SUFFIX, enabled);

            editor.remove(alarmDueElapsed);
            editor.remove(alarmDueClock);
            editor.remove(replicationsActive);

            editor.apply();
        }
    }

    protected class ServiceHandler extends ReplicationService.ServiceHandler {
        public ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.arg2) {
                case COMMAND_START_PERIODIC_REPLICATION:
                    startPeriodicReplication();
                    break;
                case COMMAND_STOP_PERIODIC_REPLICATION:
                    stopPeriodicReplication();
                    break;
                case COMMAND_DEVICE_REBOOTED:
                    resetAlarmDueTimesOnReboot();
                    break;
                case COMMAND_RESET_REPLICATION_TIMERS:
                    restartPeriodicReplications();
                    break;
                default:
                    // Do nothing
                    break;
            }

            super.handleMessage(msg);
        }
    }

    @Override
    protected Handler getHandler(Looper looper) {
        return new ServiceHandler(looper);
    }

    @Override
    public void onCreate() {
        mPrefs = getSharedPreferences(PREFERENCES_FILE_NAME, Context.MODE_PRIVATE);
        upgradePreferences();
        super.onCreate();
    }

    @Override
    public synchronized IBinder onBind(Intent intent) {
        mBound = true;
        if (isPeriodicReplicationEnabled()) {
            restartPeriodicReplications();
        } else if (startReplicationOnBind()) {
            startPeriodicReplication();
        }
        return super.onBind(intent);
    }

    @Override
    public synchronized boolean onUnbind(Intent intent) {
        super.onUnbind(intent);
        mBound = false;
        if (isPeriodicReplicationEnabled()) {
            restartPeriodicReplications();
        }
        // Ensure onRebind is called when new clients bind to the service.
        return true;
    }

    @Override
    public synchronized void onRebind(Intent intent) {
        super.onRebind(intent);
        mBound = true;
        if (isPeriodicReplicationEnabled()) {
            restartPeriodicReplications();
        } else if (startReplicationOnBind()) {
            startPeriodicReplication();
        }
    }

    @Override
    protected void startReplications() {
        super.startReplications();
        setLastAlarmTime(0);
    }

    /** Start periodic replications. */
    public synchronized void startPeriodicReplication() {
        if (!isPeriodicReplicationEnabled()) {
            setPeriodicReplicationEnabled(true);
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent alarmIntent = new Intent(this, clazz);
            alarmIntent.setAction(PeriodicReplicationReceiver.ALARM_ACTION);
            // We need to use a BroadcastReceiver rather than sending the Intent directly to the
            // Service to ensure the device wakes up if it's asleep. Sending the Intent directly
            // to the Service would be unreliable.
            PendingIntent pendingAlarmIntent = PendingIntent.getBroadcast(this, 0, alarmIntent, 0);

            long next = getNextAlarmDueElapsedTime();
            long now = SystemClock.elapsedRealtime();
            if (next < now) {
                next = now;
            }
            alarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                next,
                getIntervalInSeconds() * MILLISECONDS_IN_SECOND,
                pendingAlarmIntent);
        } else {
            Log.i(TAG, "Attempted to start an already running alarm manager");
        }
    }

    /** Stop replications currently in progress and cancel future scheduled replications. */
    public synchronized void stopPeriodicReplication() {
        if (isPeriodicReplicationEnabled()) {
            setPeriodicReplicationEnabled(false);
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent alarmIntent = new Intent(this, clazz);
            alarmIntent.setAction(PeriodicReplicationReceiver.ALARM_ACTION);
            PendingIntent pendingAlarmIntent = PendingIntent.getBroadcast(this, 0, alarmIntent, 0);

            alarmManager.cancel(pendingAlarmIntent);

            stopReplications();
        } else {
            Log.i(TAG, "Attempted to stop an already stopped alarm manager");
        }
    }

    /** Stop and restart periodic replication. */
    final protected void restartPeriodicReplications() {
        stopPeriodicReplication();
        startPeriodicReplication();
    }

    /**
     * Store the time of the next alarm both as elapsed time since boot (using
     * {@link SystemClock#elapsedRealtime()}) and as standard "wall" clock time (using
     * {@link System#currentTimeMillis()}. We generally want to set our alarms based on the elapsed
     * time since booting as that is not affected by the system clock being reset. However, we use
     * the clock time to set the alarm after a reboot as clearly in this case the time since boot is
     * useless.
     * @param intervalMillis The time interval in milliseconds until the next alarm.
     */
    private void setLastAlarmTime(long intervalMillis) {
        SharedPreferences.Editor editor = mPrefs.edit();
        String className = getClass().getName();
        editor.putLong(className + PREFERENCE_LAST_ALARM_ELAPSED_TIME_SUFFIX,
            SystemClock.elapsedRealtime() + intervalMillis);
        editor.putLong(className + PREFERENCE_LAST_ALARM_CLOCK_TIME_SUFFIX,
            System.currentTimeMillis() + intervalMillis);
        editor.apply();
    }

    /**
     * @return The SharedPreferences value indicating the time since the device was booted,
     * at which the next periodic replication should begin.
     */
    private long getNextAlarmDueElapsedTime() {
        return mPrefs.getLong(getClass().getName() + PREFERENCE_LAST_ALARM_ELAPSED_TIME_SUFFIX, 0)
            + (getIntervalInSeconds() * MILLISECONDS_IN_SECOND);
    }

    /**
     * @return The SharedPreferences value indicating the wall clock time at which the next
     * periodic replication should begin.
     */
    private long getNextAlarmDueClockTime() {
        return mPrefs.getLong(getClass().getName() + PREFERENCE_LAST_ALARM_CLOCK_TIME_SUFFIX, 0)
            + (getIntervalInSeconds() * MILLISECONDS_IN_SECOND);
    }

    /**
     * Set a flag in SharedPreferences to indicate whether periodic replications are enabled.
     * @param running true to indicate that periodic replications are enabled, otherwise false.
     */
    private void setPeriodicReplicationEnabled(boolean running) {
        SharedPreferences.Editor editor = mPrefs.edit();
        editor.putBoolean(getClass().getName() + PREFERENCE_PERIODIC_REPLICATION_ENABLED_SUFFIX, running);
        editor.apply();
    }

    /**
     * @return The value of the flag stored in SharedPreferences indicating whether periodic
     * replications are currently enabled.
     */
    private boolean isPeriodicReplicationEnabled() {
        return mPrefs.getBoolean(getClass().getName() + PREFERENCE_PERIODIC_REPLICATION_ENABLED_SUFFIX,
            false);
    }

    /**
     * Reset the alarm times stored in SharedPreferences following a reboot of the device.
     * After a reboot, the AlarmManager must be setup again so that periodic replications will
     * occur following reboot.
     */
    private void resetAlarmDueTimesOnReboot() {
        // As the device has been rebooted, we use clock time rather than elapsed time since
        // booting to set the interval for the first alarm as the elapsed time since boot will
        // have been reset.
        //
        // We subtract the current time from the next expected alarm time. If it's less than
        // zero, that means we missed an alarm when the device was off, so we schedule a
        // replication immediately.  There is a slight risk that we might set it wrongly if the
        // system clock has been reset since the last alarm was fired.  Therefore, we check that
        // we're not setting the initial interval for the alarm to any later than
        // getIntervalInSeconds() after the current time so we minimise the impact of the system
        // clock being reset an will at most have to wait for the normal interval time.
        // We don't actually setup the AlarmManager here as it is up to the subclass to determine
        // if all other conditions for the replication policy are met and determine whether to
        // restart replications after a reboot.
        setPeriodicReplicationEnabled(false);
        long initialInterval = getNextAlarmDueClockTime() - System.currentTimeMillis();
        if (initialInterval < 0) {
            initialInterval = 0;
            setLastAlarmTime(initialInterval);
        } else if (initialInterval > getIntervalInSeconds() * MILLISECONDS_IN_SECOND) {
            initialInterval = -getIntervalInSeconds() * MILLISECONDS_IN_SECOND;
            setLastAlarmTime(initialInterval);
        }
    }

    /**
     * @return The interval (in seconds) between replications depending on whether a component is
     * bound to the service or not.
     */
    private int getIntervalInSeconds() {
        if (mBound) {
            return getBoundIntervalInSeconds();
        } else {
            return getUnboundIntervalInSeconds();
        }
    }

    /**
     * @return The interval between replications to be used when a client is bound to the service.
     * To prolong battery life, this should be made as long as possible. Note that on Android 5.1
     * and above (API Level 22), if the interval is less than 60 seconds, Android expands the
     * interval to 60 seconds.
     */
    protected abstract int getBoundIntervalInSeconds();

    /**
     * @return The interval between replications to be used when no client is bound to the service.
     * To prolong battery life, this should be made as long as possible. Note that on Android 5.1
     * and above (API Level 22), if the interval is less than 60 seconds, Android expands the
     * interval to 60 seconds.
     */
    protected abstract int getUnboundIntervalInSeconds();

    /**
     * @return True if you want periodic replication to start when a client binds to the service and
     * false otherwise. This is called each when a client binds to the service, so should contain
     * any logic to determine whether replications should begin. E.g. if you want replications to
     * only happen when on WiFi, this should only return true if you are on WiFi when it is called.
     */
    protected abstract boolean startReplicationOnBind();
}

