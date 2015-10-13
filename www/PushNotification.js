var PushNotification = function() {
};

// Here is a bit of meta programming to avoid copypaste style of function definition

PushNotification.prototype.invoke_method = function(method, successCallback, errorCallback, options)
{
    if (errorCallback == null)
    {
        errorCallback = function() {};
    }

    var cb = {"success": successCallback, "failure": errorCallback};

    for (var cbType in cb)
    {
        if (cb.hasOwnProperty(cbType) && typeof cb[cbType] != "function")
        {
            console.log("PushNotification." + method + " failure: " + cbType + 
                        " callback parameter must be a function");
            return;
        }
    }

    cordova.exec(successCallback, errorCallback, "PushPlugin", method, [options]);
};

var methodWithParameters = {
//  name                                   args csv   args to invoke_method call
    // Register for push notifications. Content of [options] depends on whether we
    // are working with APNS (iOS) or GCM (Android)
    "register"                          : ["options", "options"                ], 
    // Unregister for push notifications
    "unregister"                        : ["options", "options"                ],
    // Find Amazon Web Services app for current platform registered by current user
    "aws_find_app"                      : ["options", "options"                ],
    // Create endpoint for current device in AWS app found with previous API
    "aws_add_endpoint"                  : ["name",    "{name: name}"           ],
    // Subscribe to AWS topic
    "aws_subscribe"                     : ["topic",   "{topic: topic}"         ],
    // Show toast notification on WP8
    "showToastNotification"             : ["options", "options"                ],
    // Set the application icon badge
    // TODO: add support for popular window managers on Android (no native support)
    "setApplicationIconBadgeNumber"     : ["badge"  , "{badge: badge}"         ]
};

for (var methodName in methodWithParameters)
{
    if (methodWithParameters.hasOwnProperty(methodName))
    {
       // no list size check - trusted code
       var args         = methodWithParameters[methodName][0];
       var argsToInvoke = methodWithParameters[methodName][1];

       PushNotification.prototype[methodName] = new Function(
           // function parameters of the form (last arg may vary)
           // successCallback, errorCallback, options
           "successCallback, errorCallback, " + args, 
           // function body of the form (first and last arg may vary)
           // PushNotification.prototype.invoke_method(
           //     "register", successCallback, errorCallback, options);
           "PushNotification.prototype.invoke_method(" +
               "\"" + methodName + "\", successCallback, errorCallback, " + argsToInvoke + ");");
    }
}

//-------------------------------------------------------------------

if(!window.plugins) {
    window.plugins = {};
}
if (!window.plugins.pushNotification) {
    window.plugins.pushNotification = new PushNotification();
}

if (typeof module != 'undefined' && module.exports) {
  module.exports = PushNotification;
}
