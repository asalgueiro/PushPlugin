package com.plugin.gcm;

import android.app.NotificationManager;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;

import com.amazonaws.services.sns.model.SetEndpointAttributesRequest;
import com.google.android.gcm.GCMRegistrar;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.amazonaws.services.sns.AmazonSNSClient;
import com.amazonaws.auth.CognitoCachingCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.sns.model.PlatformApplication;
import com.amazonaws.services.sns.model.ListPlatformApplicationsRequest;
import com.amazonaws.services.sns.model.ListPlatformApplicationsResult;
import com.amazonaws.services.sns.model.CreatePlatformEndpointRequest;
import com.amazonaws.services.sns.model.CreatePlatformEndpointResult;
import com.amazonaws.services.sns.model.SubscribeRequest;
import com.amazonaws.services.sns.model.InvalidParameterException;

import java.util.HashMap;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * @author awysocki
 */

public class PushPlugin extends CordovaPlugin {
    public static final String TAG = "PushPlugin";

    public static final String REGISTER = "register";
    public static final String UNREGISTER = "unregister";
    public static final String AWS_FIND_APP = "aws_find_app";
    public static final String AWS_ADD_ENDPOINT = "aws_add_endpoint";
    public static final String AWS_SUBSCRIBE = "aws_subscribe";
    public static final String EXIT = "exit";

    private static CordovaWebView gWebView;
    private static AmazonSNSClient gSnsClient;
    private static String gECB;
    private static String gSenderID;
    private static String gAWS_GCM_ApplicationArn;
    private static String gGCM_Token;
    private static String gAWS_EndpointName;
    private static String gAWS_EndpointArn;
    private static String gAWS_TopicArn;

    private static ArrayList<Bundle> gCachedExtras = new ArrayList<Bundle>();
    private static boolean gForeground = false;

    /**
     * Gets the application context from cordova's main activity.
     * @return the application context
     */
    private Context getApplicationContext() {
        return this.cordova.getActivity().getApplicationContext();
    }

    @Override
    public boolean execute(String action, JSONArray data, CallbackContext callbackContext)
    {
        new ExecuteAWSTask().execute(new AWSTaskParameters(action, data, callbackContext, this.webView));
        return true;
    }

    private class AWSTaskParameters
    {
        public AWSTaskParameters(String action, JSONArray data, CallbackContext callbackContext, CordovaWebView webView)
        {
            this.action = action;
            this.data = data;
            this.callbackContext = callbackContext;
            this.webView = webView;
        }

        public String action;
        public JSONArray data;
        public CallbackContext callbackContext;
        public CordovaWebView webView;
    }


    private class ExecuteAWSTask extends AsyncTask<AWSTaskParameters, Void, Boolean> {

        private boolean execAWSTask(String action, JSONArray data, CallbackContext callbackContext, CordovaWebView webView)
        {
            boolean isSuccess = false;
            boolean invokeCallback = true;
            String message = "";

            Log.v(TAG,"execute: action="+action+" data="+data.toString());

    // TODO: refactor this "switch" to avoid code duplication, preferably using a data driven style:
    // JS method name, arguments to extract from 'data', Java code to invoke with them

            if(REGISTER.equals(action))

            {
                try {
                    JSONObject jo = data.getJSONObject(0);

                    gWebView = webView;
                    Log.v(TAG, action + ": jo=" + jo.toString());

                    gECB = (String) jo.get("ecb");
                    gSenderID = (String) jo.get("senderID");

                    Log.v(TAG, action + ": ECB=" + gECB + " senderID=" + gSenderID);

                    GCMRegistrar.register(getApplicationContext(), gSenderID);
                    isSuccess = true;
                } catch (JSONException e) {
                    message = e.getMessage();
                    Log.e(TAG, action + ": Got JSON Exception " + message);
                }

                if (!gCachedExtras.isEmpty()) {
                    Log.v(TAG, "sending cached extras");
                    for (Bundle extras : gCachedExtras) {
                        sendExtras(extras);
                    }
                    gCachedExtras.clear();
                }
            }

            else if(UNREGISTER.equals(action))

            {
                GCMRegistrar.unregister(getApplicationContext());
                isSuccess = true;
            }

            else if(AWS_FIND_APP.equals(action))

            {
                message = "Failed to find platform app for GCM";
                if (isSuccess = awsFindPlatformApplication() && reportAwsParameters(callbackContext)) {
                    invokeCallback = false;
                }
            }

            else if(AWS_ADD_ENDPOINT.equals(action))

            {
                message = "Failed to create endpoint";
                try {
                    JSONObject jo = data.getJSONObject(0);
                    Log.v(TAG, action + ": jo=" + jo.toString());

                    String name = (String) jo.get("name");
                    Log.v(TAG, action + ": name=" + name);

                    if (isSuccess = awsCreateEndpoint(name) && reportAwsParameters(callbackContext)) {
                        invokeCallback = false;
                    }
                } catch (JSONException e) {
                    message = e.getMessage();
                    Log.e(TAG, action + ": Got JSON Exception " + message);
                }
            }

            else if(AWS_SUBSCRIBE.equals(action))

            {
                message = "Failed to subscribe";
                try {
                    JSONObject jo = data.getJSONObject(0);
                    Log.v(TAG, action + ": jo=" + jo.toString());

                    String topicArn = (String) jo.get("topic");
                    Log.v(TAG, action + ": topicArn=" + topicArn);

                    if (isSuccess = awsSubscribe(topicArn) && reportAwsParameters(callbackContext)) {
                        invokeCallback = false;
                    }
                } catch (JSONException e) {
                    message = e.getMessage();
                    Log.e(TAG, action + ": Got JSON Exception " + message);
                }
            }

            else

            {
                message = "Invalid action : " + action;
                Log.e(TAG, message);
            }

            if(invokeCallback)

            {
                if (isSuccess) {
                    callbackContext.success();
                } else {
                    callbackContext.error(message);
                }
            }

            return isSuccess;
        }

        @Override
        protected Boolean doInBackground(AWSTaskParameters[] params) {
            return new Boolean(execAWSTask(params[0].action, params[0].data, params[0].callbackContext, params[0].webView));
        }



    }


    /*
     * Sends a json object to the client as parameter to a method which is defined in gECB.
     */
    public static void sendJavascript(JSONObject _json) {
        String _d = "javascript:" + gECB + "(" + _json.toString() + ")";
        Log.v(TAG, "sendJavascript: " + _d);

        if (gECB != null && gWebView != null) {
            gWebView.sendJavascript(_d);
        }
    }

    /*
     * Gets size of chacned extras.
     */
    public static int getExtrasSize()
    {
        return gCachedExtras.size();
    }

    /*
     * Sends the pushbundle extras to the client application.
     * If the client application isn't currently active, it is cached for later processing.
     */
    public static void sendExtras(Bundle extras)
    {
        if (extras != null) {
            Log.d(TAG, "sendExtras: extras != null");
            if (gECB != null && gWebView != null) {
                Log.d(TAG, "sendExtras: sendJavascript");
                sendJavascript(convertBundleToJson(extras));
            } else {
                gCachedExtras.add(extras);
                Log.d(TAG, "sendExtras: cached extras to send at a later time. Cache size: " +
                           Integer.toString(gCachedExtras.size()) + " items");
            }
        }
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);

        // GCM application for authenticated user to get its arn will be found on demand
        // gAWS_GCM_ApplicationArn = null; // Java does this implicitly

        CognitoCachingCredentialsProvider credentialsProvider = new CognitoCachingCredentialsProvider(
            getApplicationContext(),
            "us-east-1:fba50230-f44c-423f-bf31-0a012c62cc1d", // Identity Pool ID
            Regions.US_EAST_1 // Region
        );
        gSnsClient = new AmazonSNSClient(credentialsProvider);
        gSnsClient.setRegion(Region.getRegion(Regions.US_EAST_1));

        gForeground = true;
    }

    @Override
    public void onPause(boolean multitasking) {
        super.onPause(multitasking);
        gForeground = false;
        final NotificationManager notificationManager = (NotificationManager) cordova.getActivity().getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancelAll();
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        gForeground = true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        gForeground = false;
        gECB = null;
        gWebView = null;
        gSnsClient = null;
        gAWS_GCM_ApplicationArn = null;
        gGCM_Token = null;
        gAWS_EndpointName = null;
        gAWS_EndpointArn = null;
        gAWS_TopicArn = null;
    }

    /*
     * serializes a bundle to JSON.
     */
    private static JSONObject convertBundleToJson(Bundle extras)
    {
        try
        {
            JSONObject json;
            json = new JSONObject().put("event", "message");

            JSONObject jsondata = new JSONObject();
            Iterator<String> it = extras.keySet().iterator();
            while (it.hasNext())
            {
                String key = it.next();
                Object value = extras.get(key);

                // System data from Android
                if (key.equals("from") || key.equals("collapse_key"))
                {
                    json.put(key, value);
                }
                else if (key.equals("foreground"))
                {
                    json.put(key, extras.getBoolean("foreground"));
                }
                else if (key.equals("when"))
                {
                    json.put(key, extras.getLong("when"));
                }
                else if (key.equals("coldstart"))
                {
                    json.put(key, extras.getBoolean("coldstart"));
                }
                else
                {
                    // Maintain backwards compatibility
                    if (key.equals("message") || key.equals("msgcnt") || key.equals("soundname"))
                    {
                        json.put(key, value);
                    }

                    if ( value instanceof String ) {
                    // Try to figure out if the value is another JSON object

                        String strValue = (String)value;
                        if (strValue.startsWith("{")) {
                            try {
                                JSONObject json2 = new JSONObject(strValue);
                                jsondata.put(key, json2);
                            }
                            catch (Exception e) {
                                jsondata.put(key, value);
                            }
                            // Try to figure out if the value is another JSON array
                        }
                        else if (strValue.startsWith("["))
                        {
                            try
                            {
                                JSONArray json2 = new JSONArray(strValue);
                                jsondata.put(key, json2);
                            }
                            catch (Exception e)
                            {
                                jsondata.put(key, value);
                            }
                        }
                        else
                        {
                            jsondata.put(key, value);
                        }
                    }
                }
            } // while
            json.put("payload", jsondata);

            Log.v(TAG, "extrasToJSON: " + json.toString());

            return json;
        }
        catch( JSONException e)
        {
            Log.e(TAG, "extrasToJSON: JSON exception");
        }
        return null;
    }

    // TODO: add exception handling, see http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/index.html
    //
    // AWS SDK methods can throw
    //    AuthorizationErrorException
    //    InternalErrorException
    //    InvalidParameterException
    //    AmazonClientException - If any internal errors are encountered inside the client while attempting
    //                            to make the request or handle the response. For example if a network
    //                            connection is not available.
    //    AmazonServiceException - If an error response is returned by AmazonSNS indicating either a problem
    //                            with the data in the request, or a server side issue.

    // TODO: declare AWS_Facade class hiding all AWS calls behind interrface like below rather than
    // implementing this in PushPlugin

    // TODO: strip down aws-java-sdk-1.10.20.jar, now 21Mb - leave only SNS and necessary utility API
    private static boolean awsFindPlatformApplication()
    {
        if (gAWS_GCM_ApplicationArn != null)
        {
             return true; // arn never changes while we work so we are done
        }

        // Find GCM application for authenticated user to get its arn
        ListPlatformApplicationsRequest listRequest = new ListPlatformApplicationsRequest();
        ListPlatformApplicationsResult listResult = gSnsClient.listPlatformApplications(listRequest);

        Pattern gcmPattern = Pattern.compile("arn:aws:sns:[-0-9a-z]+:\\d+:app/GCM/.*");
        for (PlatformApplication app : listResult.getPlatformApplications()) 
        {
            String arn = app.getPlatformApplicationArn();
            Matcher arnMatcher = gcmPattern.matcher(arn);

            if (arnMatcher.matches())
            {
                gAWS_GCM_ApplicationArn = arn;
                Log.d(TAG, "GCM App Arn: " + gAWS_GCM_ApplicationArn);
                break;
            }
            else
            {
                Log.d(TAG, "Not Matching App Arn: " + arn);
            }
        }

        if (gAWS_GCM_ApplicationArn == null)
        {
            Log.e(TAG, "GCM App Arn not found in a list");
        }

        return gAWS_GCM_ApplicationArn != null;
    }

    private static boolean awsCreateEndpoint(String name)
    {
        if (name == null || !awsFindPlatformApplication())
        {
             return false;
        }

        // Create platform endpoint with current token. Updates token and/or user data if endpoint exists
        CreatePlatformEndpointRequest createRequest = new CreatePlatformEndpointRequest();
        createRequest.setToken(gGCM_Token);
        gAWS_EndpointName = name;
        createRequest.setCustomUserData(gAWS_EndpointName);
        createRequest.setPlatformApplicationArn(gAWS_GCM_ApplicationArn);

        CreatePlatformEndpointResult createResult;

        try
        {
            createResult = gSnsClient.createPlatformEndpoint(createRequest);
            gAWS_EndpointArn = createResult.getEndpointArn();
        }
        catch (InvalidParameterException e)
        {
            Log.d(TAG, "InvalidParameterException: " + e.getMessage());

            Pattern existsPattern = Pattern.compile(".*Endpoint (arn:aws:sns:\\S+) already exists.*");
            Matcher existsMatcher = existsPattern.matcher(e.getMessage());

            if (existsMatcher.matches())
            {
                gAWS_EndpointArn =  existsMatcher.group(1);
                Log.d(TAG, "Extracted existing endpoint Arn: " + gAWS_EndpointArn);

                awsUpdateEndpointAttributes(gAWS_EndpointName, gGCM_Token);
            }
        }

        Log.d(TAG, "Endpoint Arn: " + gAWS_EndpointArn);

        return true;
    }

    private static void awsUpdateEndpointAttributes(String name, String token)
    {
        if (gAWS_EndpointArn == null)
        {
            return;
        }

        SetEndpointAttributesRequest setRequest = new SetEndpointAttributesRequest();
        setRequest.setEndpointArn(gAWS_EndpointArn);

        HashMap<String, String> attributes = new HashMap<String, String>();
        gAWS_EndpointName = name;
        attributes.put("CustomUserData", gAWS_EndpointName);
        gGCM_Token = token;
        attributes.put("Token", gGCM_Token);
        attributes.put("Enabled", "true");
        setRequest.setAttributes(attributes);

        gSnsClient.setEndpointAttributes(setRequest);
    }

    private static boolean awsSubscribe(String topicArn)
    {
        if (!awsCreateEndpoint(gAWS_EndpointName))
        {
             return false;
        }

        //subscribe to an SNS topic
        SubscribeRequest subRequest = new SubscribeRequest(topicArn, "application", gAWS_EndpointArn);
        gSnsClient.subscribe(subRequest);

        gAWS_TopicArn = topicArn;
        Log.d(TAG, "Endpoint " + gAWS_EndpointArn + " subscribed to topic " + gAWS_TopicArn);

        return true;
    }

    private boolean reportAwsParameters(CallbackContext context)
    {
        JSONObject awsJson;
        boolean isSuccess = false;

        try
        {
            awsJson = new JSONObject();

            if (gAWS_GCM_ApplicationArn != null)
            {
                awsJson.put("appArn", gAWS_GCM_ApplicationArn);
            }
            if (gAWS_EndpointArn != null)
            {
                awsJson.put("endpointArn", gAWS_EndpointArn);
            }
            if (gAWS_EndpointName != null)
            {
                awsJson.put("deviceName", gAWS_EndpointName);
            }
            if (gAWS_TopicArn != null)
            {
                awsJson.put("topicArn", gAWS_TopicArn);
            }

            context.success(awsJson);
            isSuccess = true;
        }
        catch (JSONException e)
        {
            Log.e(TAG, "reportAwsParameters: Got JSON Exception " + e.getMessage());
        }

        return isSuccess;
    }

    public static boolean isInForeground()
    {
      return gForeground;
    }

    public static boolean isActive()
    {
        return gWebView != null;
    }

    public static void setGCM_Token(String token)
    {
        gGCM_Token = token;

        if (gAWS_EndpointArn != null)
        {
            awsUpdateEndpointAttributes(gAWS_EndpointName, gGCM_Token);
        }
    }
}
