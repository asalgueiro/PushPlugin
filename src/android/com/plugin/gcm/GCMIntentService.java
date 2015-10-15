package com.plugin.gcm;

import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.google.android.gcm.GCMBaseIntentService;

import java.text.SimpleDateFormat;
import java.util.Date;

@SuppressLint("NewApi")
public class GCMIntentService extends GCMBaseIntentService {

    private static final String TAG = "GCMIntentService";
    private static final int notifId = 1;
    private static final String stateString[] = {"OK", "warning", "down"};
    
    public GCMIntentService() {
        super("GCMIntentService");
    }

    private String getStateString(int state) {
        return state > 0 && state <= stateString.length ? stateString[state - 1] : "<undefined>";
    }

    private String getDurationString(int dur) {
        return (dur >= (24 * 60) ? Integer.toString(dur / (24 * 60)) + "d " : "") +
               (dur % (24 * 60) >= 60 ? Integer.toString((dur % (24 * 60)) / 60) + "h " : "") +
               (dur % 60 > 0 ? Integer.toString(dur % 60) + "m" : "");
    }

    private String getStateChangeString(int state1, int state2, int duration) {
       return duration > 0 ?
               "[" + getStateString(state2) + "] for " + getDurationString(duration) :
               "[" + getStateString(state1) + "] &#8680; [" + getStateString(state2) + "]";
    }

    @Override
    public void onRegistered(Context context, String regId) {

        Log.v(TAG, "onRegistered: "+ regId);

        JSONObject json;

        try
        {
            json = new JSONObject().put("event", "registered");
            json.put("regid", regId);

            Log.v(TAG, "onRegistered: " + json.toString());

            PushPlugin.setGCM_Token(regId);
            // Send this JSON data to the JavaScript application above EVENT should be set to the msg type
            // In this case this is the registration ID
            PushPlugin.sendJavascript(json);

        }
        catch(JSONException e)
        {
            // No message to the user is sent, JSON failed
            Log.e(TAG, "onRegistered: JSON exception");
        }
    }

    @Override
    public void onUnregistered(Context context, String regId) {
        Log.d(TAG, "onUnregistered - regId: " + regId);
        PushPlugin.setGCM_Token(null);
    }

    @Override
    protected void onMessage(Context context, Intent intent) {
        Log.d(TAG, "onMessage - context: " + context);

        // Extract the payload from the message
        Bundle extras = intent.getExtras();
        if (extras != null)
        {
            Log.d(TAG, "onMessage - extras present");
            // if we are in the foreground, just surface the payload, else post it to the statusbar
            extras.putBoolean("foreground", PushPlugin.isInForeground());
            extras.putLong("when", System.currentTimeMillis());

            createNotification(context, extras);

            Log.d(TAG, "onMessage - call PushPlugin.sendExtras()");
            PushPlugin.sendExtras(extras);
        }
    }

    public void createNotification(Context context, Bundle extras)
    {
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String appName = getAppName(this);

        Intent notificationIntent = new Intent(this, PushHandlerActivity.class);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        notificationIntent.putExtra("pushBundle", extras);

        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        
        int defaults = Notification.DEFAULT_ALL;

        if (extras.getString("defaults") != null) {
            try {
                defaults = Integer.parseInt(extras.getString("defaults"));
            } catch (NumberFormatException e) {}
        }
        
        String dateString = null;
        String title = "<no title>";
        String name = "<no name>";
        int state1 = 0;
        int state2 = 0;
        int duration = 0;
        String message = "<no message>";

        if (extras.containsKey("default"))
        { 
            // AWS always sends payload within a "default" wrapper but we consider it optional
            try
            {
                JSONObject json = new JSONObject(extras.getString("default"));
                dateString = json.optString("t", null);
                name = json.optString("n", "<no name>");
                state1 = Integer.parseInt(json.optString("s1", "0"));
                state2 = Integer.parseInt(json.optString("s2", "0"));
                duration = Integer.parseInt(json.optString("w", "0"));
                message = json.optString("m", "<no message>");
            }
            catch(JSONException e)
            {
                Log.e(TAG, "createNotification: JSON exception for 'default' payload when constructing a new notification");
            }
            catch (NumberFormatException e) 
            { 
                Log.e(TAG, "createNotification: failed to parse integer parameter");  
            }   
        }
        else
        {
            try
            {
                dateString = extras.containsKey("t") ? extras.getString("t") : null;
                name = extras.containsKey("n") ? extras.getString("n") : "<no name>";
                state1 = extras.containsKey("s1") ? Integer.parseInt(extras.getString("s1")) : 0;
                state2 = extras.containsKey("s2") ? Integer.parseInt(extras.getString("s2")) : 0;
                duration = extras.containsKey("w") ? Integer.parseInt(extras.getString("w")) : 0;
                message = extras.containsKey("m") ? extras.getString("m") : "<no message>";
            }
            catch (NumberFormatException e) 
            { 
                Log.e(TAG, "createNotification: failed to parse integer parameter");  
            }   
        }

        Date pDateTime = dateString == null ? new Date() : new Date(Long.parseLong(dateString));
        String pDate = new SimpleDateFormat("MM/dd/yyyy").format(pDateTime);
        String pTime = new SimpleDateFormat("HH:mm:ss").format(pDateTime);

        title = name + " " + getStateChangeString(state1, state2, duration) + " at " + pDate + " " + pTime;

        int extrasSize = PushPlugin.getExtrasSize();
        if (extrasSize > 0) {
            message = "Last: " + title;
            title = Integer.toString(extrasSize + 1) + " alerts";
        }

        NotificationCompat.Builder mBuilder =
            new NotificationCompat.Builder(context)
                .setDefaults(defaults)
                .setSmallIcon(com.phonegap.helloworld.R.drawable.ic_stat_ip)
                .setWhen(System.currentTimeMillis())
                .setContentTitle(title)
                .setTicker(title)
                .setContentIntent(contentIntent)
                .setAutoCancel(true);

        mBuilder.setContentText(message != null ? message : "<no message>");

        String msgcnt = extras.getString("msgcnt");
        if (msgcnt != null) {
            mBuilder.setNumber(Integer.parseInt(msgcnt));
        }
        
        Log.d(TAG, "createNotification(): id = " + Integer.toString(notifId));
        mNotificationManager.notify((String) appName, notifId, mBuilder.build());
    }
    
    private static String getAppName(Context context)
    {
        CharSequence appName = 
                context
                    .getPackageManager()
                    .getApplicationLabel(context.getApplicationInfo());
        
        return (String)appName;
    }
    
    @Override
    public void onError(Context context, String errorId) {
        Log.e(TAG, "onError - errorId: " + errorId);
    }

}
