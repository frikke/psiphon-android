/*
 *
 * Copyright (c) 2019, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3;

import android.app.ActivityManager;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.multidex.MultiDex;

import com.psiphon3.log.MyLog;
import com.psiphon3.psiphonlibrary.LocaleManager;
import com.psiphon3.psiphonlibrary.PsiphonConstants;
import com.psiphon3.psiphonlibrary.TunnelServiceInteractor;
import com.psiphon3.psiphonlibrary.TunnelVpnService;
import com.psiphon3.psiphonlibrary.Utils;

import java.io.IOException;

import io.reactivex.exceptions.OnErrorNotImplementedException;
import io.reactivex.exceptions.UndeliverableException;
import io.reactivex.plugins.RxJavaPlugins;
import ru.ivanarh.jndcrash.NDCrash;
import ru.ivanarh.jndcrash.NDCrashError;
import ru.ivanarh.jndcrash.NDCrashUnwinder;

public class PsiphonApplication extends Application implements MyLog.ILogger {
    private static boolean isMainProcess(@NonNull Context context) {
        final int pid = Process.myPid();
        final String packageName = context.getPackageName();
        final ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            for (final ActivityManager.RunningAppProcessInfo info : manager.getRunningAppProcesses()) {
                if (info.pid == pid) {
                    return packageName.equals(info.processName);
                }
            }
        }
        return true;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level == TRIM_MEMORY_UI_HIDDEN && isMainProcess(this)) {
            TunnelServiceInteractor tunnelServiceInteractor = new TunnelServiceInteractor(getApplicationContext(), false);
            // bind to the tunnel service
            tunnelServiceInteractor.onStart(this);
            // send TRIM_MEMORY_UI_HIDDEN msg to the tunnel service
            tunnelServiceInteractor.messageTrimMemoryUiHidden();
            // send UNREGISTER msg to the tunnel service and unbind
            tunnelServiceInteractor.onStop(this);
            // Unregister SERVICE_STARTING_BROADCAST_INTENT receiver
            tunnelServiceInteractor.onDestroy(this);
        }
    }
    @Override
    protected void attachBaseContext(Context base) {
        // We need to make all classes available prior to calling LocaleManager by installing all
        // dexes first. There's a bit of a chicken-egg problem with calling MiltiDex.install() before
        // super.attachBaseContext() since MiltiDex.install() calls the following piece of code:
        /*
        try {
            ApplicationInfo applicationInfo = getApplicationInfo(context);
            if (applicationInfo == null) {
                Log.i("MultiDex", "No ApplicationInfo available, i.e. running on a test Context: MultiDex support library is disabled.");
                return;
            }
        */
        // but calling getApplicationInfo() on the current Context object prior to calling
        // super.attachBaseContext() produces an NPE because super.mBase is not initialized yet, see
        // corresponding methods in ContextWrapper:
        /*
        protected void attachBaseContext(Context base) {
            if (mBase != null) {
                throw new IllegalStateException("Base context already set");
            }
            mBase = base;
        }
        ...
        @Override
        public ApplicationInfo getApplicationInfo() {
            return mBase.getApplicationInfo();
        }
        */
        // We are going to wrap the current Context object with a ContextWrapper to make sure that proper
        // ApplicationInfo is returned upon calling getApplicationInfo() on this object and then use it
        // as MultiDex.install() argument.

        ContextWrapper wrappedContext = new ContextWrapper(base);
        MultiDex.install(wrappedContext);

        // Do not set locale in the base context if we detected system language should be used
        // because it will prevent locale change when it is triggered via onConfigurationChanged
        // callback when user changes locale in the OS settings.
        LocaleManager localeManager = LocaleManager.getInstance(base);
        if (localeManager.isSetToSystemLocale()) {
            super.attachBaseContext(base);
        } else {
            super.attachBaseContext(localeManager.setLocale(base));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        final String reportPath = PsiphonCrashService.getTempCrashReportPath(this);
        final NDCrashError error = NDCrash.initializeOutOfProcess(
                this,
                reportPath,
                NDCrashUnwinder.libunwind,
                PsiphonCrashService.class);
        if (error != NDCrashError.ok) {
            // Initialization failed, log the error.
            MyLog.e("NDCrash library initialization error: " + error.name());
        }

        MyLog.setLogger(this);
        // Make sure VPN service is ALWAYS enabled because app upgrade will not automatically re-enable it
        PackageManager packageManager = getPackageManager();
        ComponentName componentName = new ComponentName(getPackageName(), TunnelVpnService.class.getName());
        packageManager.setComponentEnabledSetting(componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);

        PsiphonConstants.DEBUG = Utils.isDebugMode(this);

        // If an Rx subscription is disposed while the observable is still running its async task
        // which may throw an error the error will have nowhere to go and will result in an uncaught
        // UndeliverableException being thrown. We are going to set up a global error handler to make
        // sure the app is not crashed in this case. For more details see
        // https://github.com/ReactiveX/RxJava/wiki/What's-different-in-2.0#error-handling
        RxJavaPlugins.setErrorHandler(e -> {
            if (e instanceof UndeliverableException) {
                e = e.getCause();
            }
            if ((e instanceof IOException)) {
                // fine, irrelevant network problem or API that throws on cancellation
                return;
            }
            if (e instanceof InterruptedException) {
                // fine, some blocking code was interrupted by a dispose call
                return;
            }
            MyLog.w("RxJava undeliverable exception received: " + e);

            // Also crash if OnErrorNotImplementedException
            if (e instanceof OnErrorNotImplementedException) {
                Thread currentThread = Thread.currentThread();
                currentThread.getUncaughtExceptionHandler().uncaughtException(currentThread, e);
            }
        });
    }

    @Override
    public Context getContext() {
        return this;
    }
}
