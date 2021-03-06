package com.imsd.radio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioTrack;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.RemoteViews;

import com.spoledge.aacdecoder.MultiPlayer;
import com.spoledge.aacdecoder.PlayerCallback;

import java.util.ArrayList;
import java.util.List;

//import co.mobiwise.library.R;


/**
 * Created by mertsimsek on 01/07/15.
 */
public class RadioPlayerService extends Service implements PlayerCallback {

    private FakeR fakeR;
    /**
     * Notification action intent strings
     */
    private static final String NOTIFICATION_INTENT_PLAY_PAUSE = "com.imsd.radio.INTENT_PLAYPAUSE";

    private static final String NOTIFICATION_INTENT_CANCEL = "com.imsd.radio.INTENT_CANCEL";

    private static final String NOTIFICATION_INTENT_OPEN_PLAYER = "com.imsd.radio.INTENT_OPENPLAYER";

    /**
     * Notification current values
     */
    private String singerName = "";
    private String songName = "";
    private int smallImage;
    private Bitmap artImage;

    /**
     * Notification ID
     */
    private static final int NOTIFICATION_ID = 001;
    private static final String NOTIFICATION_CHANNEL_ID = "nc_now_playing";

    /**
     * Logging control variable
     */
    private static boolean isLogging = false;

    /**
     * Radio buffer and decode capacity(DEFAULT VALUES)
     */
    private final int AUDIO_BUFFER_CAPACITY_MS = 800;
    private final int AUDIO_DECODE_CAPACITY_MS = 400;

    /**
     * Stream url suffix
     */
    private final String SUFFIX_PLS = ".pls";
    private final String SUFFIX_RAM = ".ram";
    private final String SUFFIX_WAX = ".wax";

    /**
     * State enum for Radio Player state (IDLE, PLAYING, STOPPED, INTERRUPTED)
     */
    public enum State {
        IDLE,
        PLAYING,
        STOPPED,
    }

    List<RadioListener> mListenerList;

    /**
     * Radio State
     */
    private State mRadioState;

    /**
     * Current radio URL
     */
    private String mRadioUrl;

    /**
     * Stop action. If another mediaplayer will start.It needs
     * to send broadcast to stop this service.
     */
    public static final String ACTION_MEDIAPLAYER_STOP = "co.mobiwise.library.ACTION_STOP_MEDIAPLAYER";

    /**
     * AAC Radio Player
     */
    private MultiPlayer mRadioPlayer;

    /**
     * Will be controlled on incoming calls and stop and start player.
     */
    private TelephonyManager mTelephonyManager;

    /**
     * While current radio playing, if you give another play command with different
     * source, you need to stop it first. This value is responsible for control
     * after radio stopped.
     */
    private boolean isSwitching;

    /**
     * If closed from notification, it will be checked
     * on Stop method and notification will not be created
     */
    private boolean isClosedFromNotification = false;

    /**
     * Incoming calls interrupt radio if it is playing.
     * Check if this is true or not after hang up;
     */
    private boolean isInterrupted;

    /**
     * If play method is called repeatedly, AAC Decoder will be failed.
     * play and stop methods will be turned mLock = true when they called,
     *
     * @onRadioStarted and @onRadioStopped methods will be release lock.
     */
    private boolean mLock;

    /**
     * Notification manager
     */
    private NotificationManager mNotificationManager;

    /**
     * Binder
     */
    public final IBinder mLocalBinder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mLocalBinder;
    }

    /**
     * Binder
     */
    public class LocalBinder extends Binder {
        public RadioPlayerService getService() {
            return RadioPlayerService.this;
        }
    }

    /**
     * Service called
     *
     * @param intent
     * @param flags
     * @param startId
     * @return
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        Log.d("Intent received by RadioPlayerService:", action);

        /**
         * If cancel clicked on notification, then set state to
         * IDLE, stop player and cancel notification
         */
        if (action.equals(NOTIFICATION_INTENT_CANCEL)) {
            if (isPlaying()) {
                isClosedFromNotification = true;
                stop();
            }
            // if (mNotificationManager != null)
            //     mNotificationManager.cancel(NOTIFICATION_ID);
        }
        /**
         * If play/pause action clicked on notification,
         * Check player state and stop/play streaming.
         */
        else if (action.equals(NOTIFICATION_INTENT_PLAY_PAUSE)) {
            if (isPlaying())
                stop();
            else if (mRadioUrl != null)
                play(mRadioUrl);

        } else if (action.equals(NOTIFICATION_INTENT_OPEN_PLAYER)) {
            PackageManager packageManager = getPackageManager();
            Intent i = packageManager.getLaunchIntentForPackage(getPackageName());
            if (i != null) {
                i.setPackage(null);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(i);
            }
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        fakeR = new FakeR(this);
        smallImage = fakeR.getId("drawable", "icon");

        mListenerList = new ArrayList<RadioListener>();

        mRadioState = State.IDLE;
        isSwitching = false;
        isInterrupted = false;
        mLock = false;
        getPlayer();

        mTelephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (mTelephonyManager != null)
            mTelephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
    }

    /**
     * Play url if different from previous streaming url.
     *
     * @param mRadioUrl
     * @param singerName
     * @param songName
     */
    public void play(String mRadioUrl, String singerName, String songName) {
      this.singerName = singerName;
      this.songName = songName;
      this.play(mRadioUrl);
    }

    /**
     * Play url if different from previous streaming url.
     *
     * @param mRadioUrl
     */
    public void play(String mRadioUrl) {

        sendBroadcast(new Intent(ACTION_MEDIAPLAYER_STOP));

        notifyRadioLoading();

        if (checkSuffix(mRadioUrl))
            decodeStremLink(mRadioUrl);
        else {
            this.mRadioUrl = mRadioUrl;
            isSwitching = false;

            if (isPlaying()) {
                log("Switching Radio");
                isSwitching = true;
                stop();
            } else if (!mLock) {
                log("Play requested.");
                mLock = true;
                getPlayer().playAsync(mRadioUrl);
            }
        }
    }

    public void stop() {
        if (!mLock && mRadioState != State.STOPPED) {
            log("Stop requested.");
            mLock = true;
            getPlayer().stop();
        }
    }

    @Override
    public void playerStarted() {
        mRadioState = State.PLAYING;
        buildNotification();
        mLock = false;
        notifyRadioStarted();

        log("Player started. tate : " + mRadioState);

        if (isInterrupted)
            isInterrupted = false;

    }

    public boolean isPlaying() {
        if (State.PLAYING == mRadioState)
            return true;
        return false;
    }

    @Override
    public void playerPCMFeedBuffer(boolean b, int i, int i1) {
        //Empty
    }

    @Override
    public void playerStopped(int i) {
        mRadioState = State.STOPPED;

        /**
         * If player stopped from notification then dont
         * call buildNotification().
         */
         log("Player stopped. isClosedFromNotification : " + isClosedFromNotification);
        notifyRadioStopped(isClosedFromNotification);
        if (!isClosedFromNotification)
            buildNotification();
        else
            isClosedFromNotification = false;

        mLock = false;
        // mNotificationManager.cancel(NOTIFICATION_ID);
        stopForeground(true);
        log("Player stopped. State : " + mRadioState);

        if (isSwitching)
            play(mRadioUrl);
    }

    @Override
    public void playerException(Throwable throwable) {
        mLock = false;
        mRadioPlayer = null;
        getPlayer();
        notifyErrorOccured();
        log("ERROR OCCURED.");
    }

    @Override
    public void playerMetadata(String s, String s2) {
        notifyMetaDataChanged(s, s2);
    }

    @Override
    public void playerAudioTrackCreated(AudioTrack audioTrack) {
        //Empty
    }

    public void registerListener(RadioListener mListener) {
        mListenerList.add(mListener);
    }

    public void unregisterListener(RadioListener mListener) {
        mListenerList.remove(mListener);
    }

    private void notifyRadioStarted() {
        for (RadioListener mRadioListener : mListenerList) {
            mRadioListener.onRadioStarted();
        }
    }

    private void notifyRadioStopped(boolean closedFromNotification) {
        for (RadioListener mRadioListener : mListenerList)
            mRadioListener.onRadioStopped(closedFromNotification);
    }

    private void notifyMetaDataChanged(String s, String s2) {
        for (RadioListener mRadioListener : mListenerList)
            mRadioListener.onMetaDataReceived(s, s2);
    }

    private void notifyRadioLoading() {
        for (RadioListener mRadioListener : mListenerList) {
            mRadioListener.onRadioLoading();
        }
    }

    private void notifyErrorOccured(){
        for (RadioListener mRadioListener : mListenerList) {
            mRadioListener.onError();
        }
    }


    /**
     * Return AAC player. If it is not initialized, creates and returns.
     *
     * @return MultiPlayer
     */
    private MultiPlayer getPlayer() {
        try {

            java.net.URL.setURLStreamHandlerFactory(new java.net.URLStreamHandlerFactory() {

                public java.net.URLStreamHandler createURLStreamHandler(String protocol) {
                    Log.d("LOG", "Asking for stream handler for protocol: '" + protocol + "'");
                    if ("icy".equals(protocol))
                        return new com.spoledge.aacdecoder.IcyURLStreamHandler();
                    return null;
                }
            });
        } catch (Throwable t) {
            Log.w("LOG", "Cannot set the ICY URLStreamHandler - maybe already set ? - " + t);
        }

        if (mRadioPlayer == null) {
            mRadioPlayer = new MultiPlayer(this, AUDIO_BUFFER_CAPACITY_MS, AUDIO_DECODE_CAPACITY_MS);
            mRadioPlayer.setResponseCodeCheckEnabled(false);
            mRadioPlayer.setPlayerCallback(this);
        }
        return mRadioPlayer;
    }

    PhoneStateListener phoneStateListener = new PhoneStateListener() {
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            if (state == TelephonyManager.CALL_STATE_RINGING) {

                /**
                 * Stop radio and set interrupted if it is playing on incoming call.
                 */
                if (isPlaying()) {
                    isInterrupted = true;
                    stop();
                }

            } else if (state == TelephonyManager.CALL_STATE_IDLE) {

                /**
                 * Keep playing if it is interrupted.
                 */
                if (isInterrupted)
                    play(mRadioUrl);

            } else if (state == TelephonyManager.CALL_STATE_OFFHOOK) {

                /**
                 * Stop radio and set interrupted if it is playing on outgoing call.
                 */
                if (isPlaying()) {
                    isInterrupted = true;
                    stop();
                }

            }
            super.onCallStateChanged(state, incomingNumber);
        }
    };

    /**
     * Check supported suffix
     *
     * @param streamUrl
     * @return
     */
    public boolean checkSuffix(String streamUrl) {
        if (streamUrl.contains(SUFFIX_PLS) ||
                streamUrl.contains(SUFFIX_RAM) ||
                streamUrl.contains(SUFFIX_WAX))
            return true;
        else
            return false;
    }

    /**
     * Enable/Disable log
     *
     * @param logging
     */
    public void setLogging(boolean logging) {
        isLogging = logging;
    }

    /**
     * Logger
     *
     * @param log
     */
    private void log(String log) {
        if (isLogging)
            Log.v("RadioManager", "RadioPlayerService : " + log);
    }

    /**
     * If stream link is a file, then we
     * call stream decoder to get HTTP stream link
     * from that file.
     *
     * @param streamLink
     */
    private void decodeStremLink(String streamLink) {
        new StreamLinkDecoder(streamLink) {
            @Override
            protected void onPostExecute(String s) {
                super.onPostExecute(s);
                play(s);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Build notification
     */
    private void buildNotification() {

        /**
         * Intents
         */
        Intent intentPlayPause = new Intent(NOTIFICATION_INTENT_PLAY_PAUSE);
        Intent intentOpenPlayer = new Intent(NOTIFICATION_INTENT_OPEN_PLAYER);
        Intent intentCancel = new Intent(NOTIFICATION_INTENT_CANCEL);

        /**
         * Pending intents
         */
        PendingIntent playPausePending = PendingIntent.getService(this, 0, intentPlayPause, 0);
        PendingIntent openPending = PendingIntent.getService(this, 0, intentOpenPlayer, 0);
        PendingIntent cancelPending = PendingIntent.getService(this, 0, intentCancel, 0);

        /**
         * Remote view for normal view
         */

        RemoteViews mNotificationTemplate = new RemoteViews(this.getPackageName(), fakeR.getId("layout", "notification"));

        /**
         * set small notification texts and image
         */
        if (artImage == null)
            artImage = BitmapFactory.decodeResource(getResources(), fakeR.getId("drawable", "default_art"));

        mNotificationTemplate.setTextViewText(fakeR.getId("id", "notification_line_one"), singerName);
        mNotificationTemplate.setTextViewText(fakeR.getId("id", "notification_line_two"), songName);
        mNotificationTemplate.setImageViewResource(fakeR.getId("id", "notification_play"), isPlaying() ? fakeR.getId("drawable", "btn_playback_pause") : fakeR.getId("drawable", "btn_playback_play"));
        mNotificationTemplate.setImageViewBitmap(fakeR.getId("id", "notification_image"), artImage);

        /**
         * OnClickPending intent for collapsed notification
         */
        mNotificationTemplate.setOnClickPendingIntent(fakeR.getId("id", "notification_collapse"), cancelPending);
        mNotificationTemplate.setOnClickPendingIntent(fakeR.getId("id", "notification_play"), playPausePending);


        /**
         * Create notification channel
         */
        // The user-visible name and description of the channel
        String name = "Now Playing";
        String description = "Player info and controls for streaming radio";

        /**
         * Create notification instance
         */
        Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(smallImage)
                .setContentIntent(openPending)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .setContent(mNotificationTemplate)
                .setUsesChronometer(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel mChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
            mChannel.setDescription(description);
            mNotificationManager.createNotificationChannel(mChannel);
            builder = builder.setChannelId(NOTIFICATION_CHANNEL_ID);
        }
        Notification notification = builder.build();
        // notification.flags = Notification.FLAG_ONGOING_EVENT;

        /**
         * Expanded notification
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            RemoteViews mExpandedView = new RemoteViews(this.getPackageName(), fakeR.getId("layout", "notification_expanded"));

            mExpandedView.setTextViewText(fakeR.getId("id", "notification_line_one"), singerName);
            mExpandedView.setTextViewText(fakeR.getId("id", "notification_line_two"), songName);
            mExpandedView.setImageViewResource(fakeR.getId("id", "notification_expanded_play"), isPlaying() ? fakeR.getId("drawable", "btn_playback_pause") : fakeR.getId("drawable", "btn_playback_play"));
            mExpandedView.setImageViewBitmap(fakeR.getId("id", "notification_image"), artImage);

            mExpandedView.setOnClickPendingIntent(fakeR.getId("id", "notification_collapse"), cancelPending);
            mExpandedView.setOnClickPendingIntent(fakeR.getId("id", "notification_expanded_play"), playPausePending);

            notification.bigContentView = mExpandedView;
        }

        // if (mNotificationManager != null) {
            // mNotificationManager.notify(NOTIFICATION_ID, notification);
        // }
        startForeground(NOTIFICATION_ID, notification);
        Log.d("LOG", "Displayed notification");
    }

    public void updateNotification(String singerName, String songName, int smallImage, int artImage) {
        this.singerName = singerName;
        this.songName = songName;
        this.smallImage = smallImage;
        this.artImage = BitmapFactory.decodeResource(getResources(), artImage);
        stopForeground(true);
        buildNotification();
    }


    public void updateNotification(String singerName, String songName, int smallImage, Bitmap artImage) {
        this.singerName = singerName;
        this.songName = songName;
        this.smallImage = smallImage;
        this.artImage = artImage;
        stopForeground(true);
        buildNotification();
    }


}
