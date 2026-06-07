package com.anezium.r08companion;

import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.util.Log;

import com.rokid.cxr.link.CXRLink;

final class CxrGlobalServiceBinder {
    private static final String TAG = "R08CxrBinder";
    private static final String GLOBAL_AI_APP_PACKAGE = "com.rokid.sprite.global.aiapp";
    private static final String MEDIA_SERVICE_ACTION =
            "com.rokid.sprite.aiapp.externalapp.MEDIA_STREAM_SERVICE";
    private static final String AUTH_TOKEN_EXTRA = "auth_token";
    private static final String AUTH_PACKAGE_EXTRA = "auth_package";

    boolean bind(Context context, CXRLink cxrLink, String token) {
        try {
            ServiceConnection connection = findServiceConnection(cxrLink);
            Intent intent = new Intent(MEDIA_SERVICE_ACTION)
                    .setPackage(GLOBAL_AI_APP_PACKAGE)
                    .putExtra(AUTH_TOKEN_EXTRA, token)
                    .putExtra(AUTH_PACKAGE_EXTRA, context.getPackageName());
            return context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException exception) {
            Log.w(TAG, "global Hi Rokid bind failed", exception);
            return false;
        }
    }

    private ServiceConnection findServiceConnection(CXRLink cxrLink) {
        Class<?> type = cxrLink.getClass();
        while (type != null) {
            java.lang.reflect.Field[] fields = type.getDeclaredFields();
            for (java.lang.reflect.Field field : fields) {
                if (ServiceConnection.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        return (ServiceConnection) field.get(cxrLink);
                    } catch (IllegalAccessException exception) {
                        throw new IllegalStateException("CXR-L ServiceConnection inaccessible", exception);
                    }
                }
            }
            type = type.getSuperclass();
        }
        throw new IllegalStateException("CXR-L ServiceConnection field not found");
    }
}
