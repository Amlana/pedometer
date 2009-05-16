/*
 *  Pedometer - Android App
 *  Copyright (C) 2009 Levente Bagi
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package name.bagi.levente.pedometer;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.SensorManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.google.tts.TTS;


/**
 * This is an example of implementing an application service that runs locally
 * in the same process as the application.  The {@link StepServiceController}
 * and {@link StepServiceBinding} classes show how to interact with the
 * service.
 *
 * <p>Notice the use of the {@link NotificationManager} when interesting things
 * happen in the service.  This is generally how background services should
 * interact with the user, rather than doing something more disruptive such as
 * calling startActivity().
 */
public class StepService extends Service {

    private SharedPreferences mSettings;
    private TTS mTts;
    private SensorManager mSensorManager;
    private StepDetector mStepDetector;
//    private StepBuzzer mStepBuzzer;
    private PaceNotifier mPaceNotifier;
    private PowerManager.WakeLock wakeLock;
    private NotificationManager mNM;
    
    /**
     * Class for clients to access.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with
     * IPC.
     */
    public class StepBinder extends Binder {
        StepService getService() {
            return StepService.this;
        }
    }
    
    @Override
    public void onCreate() {
    	super.onCreate();
    	
    	mNM = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
    	showNotification();
    	
    	PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
    	wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StepService");
    	wakeLock.acquire();
    	
    	// Load settings
    	mSettings = PreferenceManager.getDefaultSharedPreferences(this);

    	// Start detecting
        mStepDetector = new StepDetector();
		mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
		mSensorManager.registerListener(mStepDetector, 
				SensorManager.SENSOR_ACCELEROMETER | 
				SensorManager.SENSOR_MAGNETIC_FIELD | 
				SensorManager.SENSOR_ORIENTATION,
				SensorManager.SENSOR_DELAY_FASTEST);
//		mStepBuzzer = new StepBuzzer(this);
		mPaceNotifier = new PaceNotifier(mPaceListener, mSettings, mTts);
//		mStepDetector.addStepListener(mStepBuzzer);
		mStepDetector.addStepListener(mStepDisplayer);
		mStepDetector.addStepListener(mPaceNotifier);
		
    	// Start voice
    	reloadSettings();
    	
    }
    
    @Override
    public void onStart(Intent intent, int startId) {
    	super.onStart(intent, startId);
    }

    @Override
    public void onDestroy() {
    	
    	mNM.cancel(R.string.app_name);

    	wakeLock.release();
    	
    	super.onDestroy();
    	
    	// Stop detecting
    	mSensorManager.unregisterListener(mStepDetector);
    	
    	// Stop voice
    	if (mTts != null) {
    		mTts.shutdown();
    	}
    	
        // Tell the user we stopped.
        Toast.makeText(this, getText(R.string.stopped), Toast.LENGTH_SHORT).show();
    }

    @Override
    public IBinder onBind(Intent intent) {
    	return mBinder;
    }

    /**
     * Receives messages from activity.
     */
    private final IBinder mBinder = new StepBinder();

    public interface ICallback {
    	public void stepsChanged(int value);
    	public void paceChanged(int value);
    }
    
    private ICallback mCallback;

    public void registerCallback(ICallback cb) {
    	mCallback = cb;
    	mStepDisplayer.passValue();
    	mPaceListener.passValue();
    }
    
    private int mDesiredPace;
    
    /**
     * Called by activity to pass the desired pace value, 
     * whenever it is modified by the user.
     * @param desiredPace
     */
    public void setDesiredPace(int desiredPace) {
    	mDesiredPace = desiredPace;
    	if (mPaceNotifier != null) {
    		mPaceNotifier.setDesiredPace(mDesiredPace);
    	}
    }
    
    public void reloadSettings() {
    	mSettings = PreferenceManager.getDefaultSharedPreferences(this);
    	
    	if (mStepDetector != null) { 
	    	mStepDetector.setSensitivity(
	    			Integer.valueOf(mSettings.getString("sensitivity", "30"))
	    	);
    	}
    	
    	boolean userWantsVoice = 
    		mSettings.getBoolean("desired_pace_enabled", true) 
    		&& mSettings.getBoolean("desired_pace_voice", false)
    		&& TTS.isInstalled(this);
    	
    	if (mTts == null && userWantsVoice) {
    		mTts = new TTS(this, null, false);
    		if (mPaceNotifier != null) {
    			mPaceNotifier.setTts(mTts);
    		}
    	}
    	else
    	if (mTts != null && ! userWantsVoice) {
    		// User turned off voice
    		mTts.shutdown();
    		mTts = null;
    		if (mPaceNotifier != null) {
    			mPaceNotifier.setTts(mTts);
    		}
    	}
    	else {
    		// No change
    	}
    }
    
    /**
     * Counts steps provided by StepDetector and passes the current
     * step count to the activity.
     */
    private StepListener mStepDisplayer = new StepListener() {
    	private int mCount = 0;
    	public void onStep() {
    		mCount ++;
    		passValue();
    	}
    	public void passValue() {
    		if (mCallback != null) {
    			mCallback.stepsChanged(mCount);
    		}
    	}
    };
    
    /**
     * Forwards pace values from PaceNotifier to the activity. 
     */
    private PaceNotifier.Listener mPaceListener = new PaceNotifier.Listener() {
    	int currentPace = 0;
    	
    	public void paceChanged(int value) {
    		currentPace = value;
    		passValue();
    	}
    	public void passValue() {
			if (mCallback != null) {
				mCallback.paceChanged(currentPace);
			}
    	}

    };
    
    /**
     * Show a notification while this service is running.
     */
    private void showNotification() {
        CharSequence text = getText(R.string.app_name);
        Notification notification = new Notification(R.drawable.ic_notification, null,
                System.currentTimeMillis());
        notification.flags = Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, Pedometer.class), 0);
        notification.setLatestEventInfo(this, text,
        		getText(R.string.notification_subtitle), contentIntent);

        mNM.notify(R.string.app_name, notification);
    }

}

