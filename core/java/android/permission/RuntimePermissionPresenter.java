/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.permission;

import static com.android.internal.util.Preconditions.checkCollectionElementsNotNull;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.function.pooled.PooledLambda;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This class provides information about runtime permissions for a specific
 * app or all apps. This information is dedicated for presentation purposes
 * and does not necessarily reflect the individual permissions requested/
 * granted to an app as the platform may be grouping permissions to improve
 * presentation and help the user make an informed choice. For example, all
 * runtime permissions in the same permission group may be presented as a
 * single permission in the UI.
 *
 * @hide
 */
public final class RuntimePermissionPresenter {
    private static final String TAG = "RuntimePermPresenter";

    /**
     * The key for retrieving the result from the returned bundle.
     *
     * @hide
     */
    public static final String KEY_RESULT =
            "android.permission.RuntimePermissionPresenter.key.result";

    /**
     * Listener for delivering the result of {@link #getAppPermissions}.
     */
    public interface OnGetAppPermissionResultCallback {
        /**
         * The result for {@link #getAppPermissions(String, OnGetAppPermissionResultCallback,
         * Handler)}.
         *
         * @param permissions The permissions list.
         */
        void onGetAppPermissions(@NonNull List<RuntimePermissionPresentationInfo> permissions);
    }

    /**
     * Listener for delivering the result of {@link #countPermissionApps}.
     */
    public interface OnCountPermissionAppsResultCallback {
        /**
         * The result for {@link #countPermissionApps(List, boolean,
         * OnCountPermissionAppsResultCallback, Handler)}.
         *
         * @param numApps The number of apps that have one of the permissions
         */
        void onCountPermissionApps(int numApps);
    }

    private static final Object sLock = new Object();

    @GuardedBy("sLock")
    private static RuntimePermissionPresenter sInstance;

    private final RemoteService mRemoteService;

    /**
     * Gets the singleton runtime permission presenter.
     *
     * @param context Context for accessing resources.
     * @return The singleton instance.
     */
    public static RuntimePermissionPresenter getInstance(@NonNull Context context) {
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new RuntimePermissionPresenter(context.getApplicationContext());
            }
            return sInstance;
        }
    }

    private RuntimePermissionPresenter(Context context) {
        mRemoteService = new RemoteService(context);
    }

    /**
     * Gets the runtime permissions for an app.
     *
     * @param packageName The package for which to query.
     * @param callback Callback to receive the result.
     * @param handler Handler on which to invoke the callback.
     */
    public void getAppPermissions(@NonNull String packageName,
            @NonNull OnGetAppPermissionResultCallback callback, @Nullable Handler handler) {
        checkNotNull(packageName);
        checkNotNull(callback);

        mRemoteService.processMessage(obtainMessage(RemoteService::getAppPermissions,
                mRemoteService, packageName, callback, handler));
    }

    /**
     * Revoke the permission {@code permissionName} for app {@code packageName}
     *
     * @param packageName The package for which to revoke
     * @param permissionName The permission to revoke
     */
    public void revokeRuntimePermission(@NonNull String packageName,
            @NonNull String permissionName) {
        checkNotNull(packageName);
        checkNotNull(permissionName);

        mRemoteService.processMessage(obtainMessage(RemoteService::revokeAppPermissions,
                mRemoteService, packageName, permissionName));
    }

    /**
     * Count how many apps have one of a set of permissions.
     *
     * @param permissionNames The permissions the app might have
     * @param countOnlyGranted Count an app only if the permission is granted to the app
     * @param countSystem Also count system apps
     * @param callback Callback to receive the result
     * @param handler Handler on which to invoke the callback
     */
    public void countPermissionApps(@NonNull List<String> permissionNames,
            boolean countOnlyGranted, boolean countSystem,
            @NonNull OnCountPermissionAppsResultCallback callback, @Nullable Handler handler) {
        checkCollectionElementsNotNull(permissionNames, "permissionNames");
        checkNotNull(callback);

        mRemoteService.processMessage(obtainMessage(RemoteService::countPermissionApps,
                mRemoteService, permissionNames, countOnlyGranted, countSystem, callback, handler));
    }

    private static final class RemoteService
            extends Handler implements ServiceConnection {
        private static final long UNBIND_TIMEOUT_MILLIS = 10000;

        public static final int MSG_UNBIND = 0;

        private final Object mLock = new Object();

        private final Context mContext;

        @GuardedBy("mLock")
        private final List<Message> mPendingWork = new ArrayList<>();

        @GuardedBy("mLock")
        private IRuntimePermissionPresenter mRemoteInstance;

        @GuardedBy("mLock")
        private boolean mBound;

        RemoteService(Context context) {
            super(context.getMainLooper(), null, false);
            mContext = context;
        }

        public void processMessage(Message message) {
            synchronized (mLock) {
                if (!mBound) {
                    Intent intent = new Intent(
                            RuntimePermissionPresenterService.SERVICE_INTERFACE);
                    intent.setPackage(mContext.getPackageManager()
                            .getPermissionControllerPackageName());
                    mBound = mContext.bindService(intent, this,
                            Context.BIND_AUTO_CREATE);
                }
                mPendingWork.add(message);
                scheduleNextMessageIfNeededLocked();
            }
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                mRemoteInstance = IRuntimePermissionPresenter.Stub.asInterface(service);
                scheduleNextMessageIfNeededLocked();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                mRemoteInstance = null;
            }
        }

        private void getAppPermissions(@NonNull String packageName,
                @NonNull OnGetAppPermissionResultCallback callback, @Nullable Handler handler) {
            final IRuntimePermissionPresenter remoteInstance;
            synchronized (mLock) {
                remoteInstance = mRemoteInstance;
            }
            if (remoteInstance == null) {
                return;
            }
            try {
                remoteInstance.getAppPermissions(packageName,
                        new RemoteCallback(result -> {
                            final List<RuntimePermissionPresentationInfo> reportedPermissions;
                            List<RuntimePermissionPresentationInfo> permissions = null;
                            if (result != null) {
                                permissions = result.getParcelableArrayList(KEY_RESULT);
                            }
                            if (permissions == null) {
                                permissions = Collections.emptyList();
                            }
                            reportedPermissions = permissions;
                            if (handler != null) {
                                handler.post(
                                        () -> callback.onGetAppPermissions(reportedPermissions));
                            } else {
                                callback.onGetAppPermissions(reportedPermissions);
                            }
                        }, this));
            } catch (RemoteException re) {
                Log.e(TAG, "Error getting app permissions", re);
            }
            scheduleUnbind();

            synchronized (mLock) {
                scheduleNextMessageIfNeededLocked();
            }
        }

        private void revokeAppPermissions(@NonNull String packageName,
                @NonNull String permissionName) {
            final IRuntimePermissionPresenter remoteInstance;
            synchronized (mLock) {
                remoteInstance = mRemoteInstance;
            }
            if (remoteInstance == null) {
                return;
            }
            try {
                remoteInstance.revokeRuntimePermission(packageName, permissionName);
            } catch (RemoteException re) {
                Log.e(TAG, "Error getting app permissions", re);
            }

            synchronized (mLock) {
                scheduleNextMessageIfNeededLocked();
            }
        }

        private void countPermissionApps(@NonNull List<String> permissionNames,
                boolean countOnlyGranted, boolean countSystem,
                @NonNull OnCountPermissionAppsResultCallback callback, @Nullable Handler handler) {
            final IRuntimePermissionPresenter remoteInstance;

            synchronized (mLock) {
                remoteInstance = mRemoteInstance;
            }
            if (remoteInstance == null) {
                return;
            }

            try {
                remoteInstance.countPermissionApps(permissionNames, countOnlyGranted, countSystem,
                        new RemoteCallback(result -> {
                            final int numApps;
                            if (result != null) {
                                numApps = result.getInt(KEY_RESULT);
                            } else {
                                numApps = 0;
                            }

                            if (handler != null) {
                                handler.post(() -> callback.onCountPermissionApps(numApps));
                            } else {
                                callback.onCountPermissionApps(numApps);
                            }
                        }, this));
            } catch (RemoteException re) {
                Log.e(TAG, "Error counting permission apps", re);
            }

            scheduleUnbind();

            synchronized (mLock) {
                scheduleNextMessageIfNeededLocked();
            }
        }

        private void unbind() {
            synchronized (mLock) {
                if (mBound) {
                    mContext.unbindService(this);
                    mBound = false;
                }
                mRemoteInstance = null;
            }
        }

        @GuardedBy("mLock")
        private void scheduleNextMessageIfNeededLocked() {
            if (mBound && mRemoteInstance != null && !mPendingWork.isEmpty()) {
                Message nextMessage = mPendingWork.remove(0);
                sendMessage(nextMessage);
            }
        }

        private void scheduleUnbind() {
            removeMessages(MSG_UNBIND);
            sendMessageDelayed(PooledLambda.obtainMessage(RemoteService::unbind, this)
                    .setWhat(MSG_UNBIND), UNBIND_TIMEOUT_MILLIS);
        }
    }
}
