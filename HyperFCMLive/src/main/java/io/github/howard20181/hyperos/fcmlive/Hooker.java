package io.github.howard20181.hyperos.fcmlive;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerExemptionManager;
import android.os.WorkSource;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import io.github.libxposed.api.XposedModule;

@SuppressLint("PrivateApi")
public class Hooker extends XposedModule {
    private static final String TAG = "HyperGreeze";
    private static final List<String> CN_DEFER_BROADCAST = Arrays.asList("com.google.android.intent.action.GCM_RECONNECT", "com.google.android.gcm.DISCONNECTED", "com.google.android.gcm.CONNECTED", "com.google.android.gms.gcm.HEARTBEAT_ALARM");
    private static final String ACTION_REMOTE_INTENT = "com.google.android.c2dm.intent.RECEIVE";
    private static final String GMS_PACKAGE_NAME = "com.google.android.gms";
    private static final String GMS_PERSISTENT_PROCESS_NAME = "com.google.android.gms.persistent";

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        var classLoader = param.getClassLoader();
        try {
            try {
                hookGreezeManagerService(classLoader);
            } catch (Exception t) {
                log(Log.ERROR, TAG, "Failed to hook GreezeManagerService", t);
            }
            try {
                hookDomesticPolicyManager(classLoader);
            } catch (Exception t) {
                log(Log.ERROR, TAG, "Failed to hook DomesticPolicyManager", t);
            }
            try {
                hookListAppsManager(classLoader);
            } catch (Exception t) {
                log(Log.ERROR, TAG, "Failed to hook ListAppsManager", t);
            }
            try {
                hookBroadcastQueueModernStubImpl(classLoader);
            } catch (Exception e) {
                log(Log.ERROR, TAG, "Failed to hook BroadcastQueueModernStubImpl", e);
            }
            try {
                hookProcessPolicy(classLoader);
            } catch (Exception e) {
                log(Log.ERROR, TAG, "Failed to hook ProcessPolicy", e);
            }
            try {
                hookAwareResourceControl(classLoader);
            } catch (Exception e) {
                log(Log.ERROR, TAG, "Failed to hook AwareResourceControl", e);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    hookPowerManagerService(classLoader);
                } catch (Exception e) {
                    log(Log.ERROR, TAG, "Failed to hook PowerManagerService", e);
                }
            }
            try {
                hookActivityManagerService(classLoader);
            } catch (Exception e) {
                log(Log.ERROR, TAG, "Failed to hook ActivityManagerService", e);
            }
        } catch (Throwable tr) {
            log(Log.ERROR, TAG, "Failed to hook SystemServer", tr);
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        var classLoader = param.getClassLoader();
        var packageName = param.getPackageName();
        if ("com.miui.powerkeeper".equals(packageName)) {
            try {
                hookGmsObserver(classLoader);
            } catch (Exception e) {
                log(Log.ERROR, TAG, "Failed to hook GmsObserver", e);
            }
            try {
                hookGlobalFeatureConfigureHelper(classLoader);
            } catch (Exception e) {
                log(Log.ERROR, TAG, "Failed to hook GlobalFeatureConfigureHelper", e);
            }
        }
    }

    private void hookGreezeManagerService(ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        var GreezeManagerServiceClass = classLoader.loadClass("com.miui.server.greeze.GreezeManagerService");
        try {
            // am.ProcessRecord app = BroadcastProcessQueue.app, app nullable
            // but when app is null, this method will not call
            // calleePkgName = (app.info == null || app.info.packageName == null) ? app.processName : app.info.packageName
            // It could be the process name.
            // boolean isAllowBroadcast(int callerUid, String callerPkgName, int calleeUid, String calleePkgName, String action)
            var isAllowBroadcastMethod = GreezeManagerServiceClass.getDeclaredMethod("isAllowBroadcast", int.class, String.class, int.class, String.class, String.class);
            hook(isAllowBroadcastMethod).intercept(chain -> {
                if ((chain.getArg(1) instanceof String callerPkgName
                        // callerPkgName get from intent or BroadcastRecord.callerPackage,
                        // both are nullable, but they won't become null in FCM broadcasts.
                        && GMS_PACKAGE_NAME.equals(callerPkgName) ||
                        chain.getArg(3) instanceof String calleePkgName
                                // calleePkgName may be process name
                                && calleePkgName.startsWith(GMS_PACKAGE_NAME))
                        && chain.getArg(4) instanceof String action
                        && (ACTION_REMOTE_INTENT.equals(action)
                        || CN_DEFER_BROADCAST.contains(action))) {
                    return true;
                }
                return chain.proceed();
            });
            deoptimize(isAllowBroadcastMethod);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook GreezeManagerService#isAllowBroadcast", e);
        }
        try {
            // boolean deferBroadcastForMiui(String action)
            var deferBroadcastForMiuiMethod = GreezeManagerServiceClass.getDeclaredMethod("deferBroadcastForMiui", String.class);
            hook(deferBroadcastForMiuiMethod).intercept(chain -> {
                if (chain.getArg(0) instanceof String action
                        && CN_DEFER_BROADCAST.contains(action)) {
                    return false;
                }
                return chain.proceed();
            });
            deoptimize(deferBroadcastForMiuiMethod);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook GreezeManagerService#deferBroadcastForMiui", e);
        }
        var triggerGMSLimitActionMethod = GreezeManagerServiceClass.getDeclaredMethod("triggerGMSLimitAction", boolean.class);
        hook(triggerGMSLimitActionMethod).intercept(chain -> {
            var args = chain.getArgs().toArray();
            args[0] = false;
            return chain.proceed(args);
        });
        deoptimize(triggerGMSLimitActionMethod);
    }

    private void hookDomesticPolicyManager(ClassLoader classLoader) throws ClassNotFoundException,
            NoSuchMethodException {
        var DomesticPolicyManagerClass = classLoader.loadClass("com.miui.server.greeze.DomesticPolicyManager");
        // boolean deferBroadcast(String action)
        var deferBroadcastMethod = DomesticPolicyManagerClass.getDeclaredMethod("deferBroadcast", String.class);
        hook(deferBroadcastMethod).intercept(chain -> false);
        deoptimize(deferBroadcastMethod);
    }

    private void hookListAppsManager(ClassLoader classLoader) throws ClassNotFoundException,
            NoSuchFieldException {
        var ListAppsManagerClass = classLoader.loadClass("com.miui.server.greeze.power.ListAppsManager");
        var mSystemBlackListField = ListAppsManagerClass.getDeclaredField("mSystemBlackList");
        mSystemBlackListField.setAccessible(true);
        var PowerStrategyModeConstructors = ListAppsManagerClass.getDeclaredConstructors();
        for (var constructor : PowerStrategyModeConstructors) {
            hook(constructor).intercept(chain -> {
                try {
                    return chain.proceed();
                } finally {
                    try {
                        var mSystemBlackList = (List<String>) mSystemBlackListField.get(chain.getThisObject());
                        if (mSystemBlackList != null) {
                            mSystemBlackList.remove(GMS_PACKAGE_NAME);
                        }
                    } catch (Exception e) {
                        log(Log.ERROR, TAG, "Failed to modify ListAppsManager$PowerStrategyMode constructor", e);
                    }
                }
            });
            deoptimize(constructor);
        }
    }

    private void hookBroadcastQueueModernStubImpl(ClassLoader classLoader) throws
            ClassNotFoundException, NoSuchMethodException, NoSuchFieldException {
        var BroadcastQueueModernStubImplClass = classLoader.loadClass("com.android.server.am.BroadcastQueueModernStubImpl");
        var BroadcastQueueClass = classLoader.loadClass("com.android.server.am.BroadcastQueue");
        var BroadcastRecordClass = classLoader.loadClass("com.android.server.am.BroadcastRecord");
        var callerPackageField = BroadcastRecordClass.getDeclaredField("callerPackage");
        callerPackageField.setAccessible(true);
        var intentField = BroadcastRecordClass.getDeclaredField("intent");
        intentField.setAccessible(true);
        var checkApplicationAutoStartMethod = BroadcastQueueModernStubImplClass.getDeclaredMethod("checkApplicationAutoStart", BroadcastQueueClass, BroadcastRecordClass, ResolveInfo.class);
        hook(checkApplicationAutoStartMethod).intercept(chain -> {
            try {
                var broadcastRecord = chain.getArg(1);
                if (callerPackageField.get(broadcastRecord) instanceof String callerPackage
                        && GMS_PACKAGE_NAME.equals(callerPackage) // BroadcastRecord.callerPackage nullable
                        && intentField.get(broadcastRecord) instanceof Intent intent
                        && ACTION_REMOTE_INTENT.equals(intent.getAction())) {
                    return true;
                }
            } catch (Exception e) {
                log(Log.ERROR, TAG, "Failed to modify BroadcastQueueModernStubImpl#checkApplicationAutoStart", e);
            }
            return chain.proceed();
        });
        deoptimize(checkApplicationAutoStartMethod);
    }

    private void hookProcessPolicy(ClassLoader classLoader) throws ClassNotFoundException,
            NoSuchMethodException {
        var ProcessPolicyClass = classLoader.loadClass("com.android.server.am.ProcessPolicy");
        var getWhiteListMethod = ProcessPolicyClass.getDeclaredMethod("getWhiteList", int.class);
        hook(getWhiteListMethod).intercept(chain -> {
            var result = chain.proceed();
            if (chain.getArg(0) instanceof Integer flags && (flags & 1) != 0) {
                if (result instanceof List<?>) {
                    var whiteList = (List<String>) result;
                    whiteList.add(GMS_PACKAGE_NAME);
                    whiteList.add(GMS_PERSISTENT_PROCESS_NAME);
                }
            }
            return result;
        });
    }

    private void hookAwareResourceControl(ClassLoader classLoader) throws ClassNotFoundException,
            NoSuchFieldException {
        var AwareResourceControlClass = classLoader.loadClass("com.miui.server.greeze.power.AwareResourceControl");
        var mNoNetworkBlackUidsField = AwareResourceControlClass.getDeclaredField("mNoNetworkBlackUids");
        mNoNetworkBlackUidsField.setAccessible(true);
        var AwareResourceControlConstructors = AwareResourceControlClass.getDeclaredConstructors();
        for (var constructor : AwareResourceControlConstructors) {
            hook(constructor).intercept(chain -> {
                try {
                    return chain.proceed();
                } finally {
                    try {
                        var mNoNetworkBlackUids = (List<String>) mNoNetworkBlackUidsField.get(chain.getThisObject());
                        if (mNoNetworkBlackUids != null) {
                            mNoNetworkBlackUids.remove(GMS_PACKAGE_NAME);
                        }
                    } catch (Exception e) {
                        log(Log.ERROR, TAG, "Failed to modify AwareResourceControl constructor", e);
                    }
                }
            });
            deoptimize(constructor);
        }
    }

    private void hookGmsObserver(ClassLoader classLoader) throws ClassNotFoundException,
            NoSuchMethodException {
        var NetdExecutorClass = classLoader.loadClass("com.miui.powerkeeper.utils.NetdExecutor");
        var initGmsChainMethod = NetdExecutorClass.getDeclaredMethod("initGmsChain", String.class, int.class, String.class);
        hook(initGmsChainMethod).intercept(chain -> {
            var args = chain.getArgs().toArray();
            args[2] = "ACCEPT";
            return chain.proceed(args);
        });
        deoptimize(initGmsChainMethod);
        var GmsObserverClass = classLoader.loadClass("com.miui.powerkeeper.utils.GmsObserver");
        Hooker hooker = chain -> {
            var args = chain.getArgs().toArray();
            args[0] = false;
            return chain.proceed(args);
        };
        var updateGmsAlarmMethod = GmsObserverClass.getDeclaredMethod("updateGmsAlarm", boolean.class);
        hook(updateGmsAlarmMethod).intercept(hooker);
        deoptimize(updateGmsAlarmMethod);
        var updateGmsNetWorkMethod = GmsObserverClass.getDeclaredMethod("updateGmsNetWork", boolean.class);
        hook(updateGmsNetWorkMethod).intercept(hooker);
        deoptimize(updateGmsNetWorkMethod);
        var updateGoogleReletivesWakelockMethod = GmsObserverClass.getDeclaredMethod("updateGoogleReletivesWakelock", boolean.class);
        hook(updateGoogleReletivesWakelockMethod).intercept(hooker);
        deoptimize(updateGoogleReletivesWakelockMethod);
    }

    private void hookGlobalFeatureConfigureHelper(ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        var GlobalFeatureConfigureHelperClass = classLoader.loadClass("com.miui.powerkeeper.provider.GlobalFeatureConfigureHelper");
        var getDozeWhiteListAppsMethod = GlobalFeatureConfigureHelperClass.getDeclaredMethod("getDozeWhiteListApps", Bundle.class);
        hook(getDozeWhiteListAppsMethod).intercept(chain -> {
            var result = chain.proceed();
            if (result instanceof List<?>) {
                var whiteList = (List<String>) result;
                if (!whiteList.contains(GMS_PACKAGE_NAME)) {
                    whiteList.add(GMS_PACKAGE_NAME);
                }
            }
            return result;
        });
    }

    private static PowerExemptionManager powerExemptionManager = null;

    @RequiresApi(Build.VERSION_CODES.S)
    private static PowerExemptionManager getPowerExemptionManager(Context context) {
        if (powerExemptionManager == null) {
            // mContext.getSystemService("power_exemption")
            powerExemptionManager = new PowerExemptionManager(context);
        }
        return powerExemptionManager;
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void hookPowerManagerService(ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException, NoSuchFieldException {
        var IWakeLockCallbackClass = classLoader.loadClass("android.os.IWakeLockCallback");
        var PowerManagerServiceClass = classLoader.loadClass("com.android.server.power.PowerManagerService");
        var mContextField = PowerManagerServiceClass.getDeclaredField("mContext");
        mContextField.setAccessible(true);
        // acquireWakeLockInternal(IBinder lock, int displayId, int flags, String tag, String packageName, WorkSource ws, String historyTag, int uid, int pid, IWakeLockCallback callback)
        var acquireWakeLockInternalMethod = PowerManagerServiceClass.getDeclaredMethod("acquireWakeLockInternal", IBinder.class, int.class, int.class, String.class, String.class, WorkSource.class, String.class, int.class, int.class, IWakeLockCallbackClass);
        hook(acquireWakeLockInternalMethod).intercept(chain -> {
            if (chain.getArg(3) instanceof String tag && "GOOGLE_C2DM".equals(tag)
                    && chain.getArg(4) instanceof String packageName) {
                if (mContextField.get(chain.getThisObject()) instanceof Context mContext) {
                    getPowerExemptionManager(mContext).addToTemporaryAllowList(
                            packageName, 102 /* PowerExemptionManager.REASON_PUSH_MESSAGING_OVER_QUOTA */,
                            tag, 2000);
                }
            }
            return chain.proceed();
        });
        deoptimize(acquireWakeLockInternalMethod);
    }

    private void hookActivityManagerService(ClassLoader classLoader) throws ClassNotFoundException,
            NoSuchMethodException, NoSuchFieldException {
        var ActivityManagerServiceClass = classLoader.loadClass("com.android.server.am.ActivityManagerService");
        var mContextField = ActivityManagerServiceClass.getDeclaredField("mContext");
        mContextField.setAccessible(true);
        var IApplicationThreadClass = classLoader.loadClass("android.app.IApplicationThread");
        var IIntentReceiverClass = classLoader.loadClass("android.content.IIntentReceiver");
        var ProcessRecordClass = classLoader.loadClass("com.android.server.am.ProcessRecord");
        var infoField = ProcessRecordClass.getDeclaredField("info");
        infoField.setAccessible(true);
        Method getRecordMethod;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12~16
            getRecordMethod = ActivityManagerServiceClass.getDeclaredMethod("getRecordForAppLOSP", IApplicationThreadClass);
        } else {
            // Android 8~11
            getRecordMethod = ActivityManagerServiceClass.getDeclaredMethod("getRecordForAppLocked", IApplicationThreadClass);
        }
        Method broadcastMethod;
        int intentArgIndex;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // int broadcastIntentWithFeature(IApplicationThread caller, String callingFeatureId,
            //    Intent intent, String resolvedType, IIntentReceiver resultTo,
            //    int resultCode, String resultData, Bundle resultExtras,
            //    String[] requiredPermissions, String[] excludedPermissions,
            //    String[] excludedPackages, int appOp, Bundle bOptions,
            //    boolean serialized, boolean sticky, int userId)
            intentArgIndex = 2;
            broadcastMethod = ActivityManagerServiceClass.getDeclaredMethod("broadcastIntentWithFeature",
                    IApplicationThreadClass, String.class,
                    Intent.class, String.class, IIntentReceiverClass,
                    int.class, String.class, Bundle.class,
                    String[].class, String[].class,
                    String[].class, int.class, Bundle.class,
                    boolean.class, boolean.class, int.class);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // int broadcastIntentWithFeature(IApplicationThread caller, String callingFeatureId,
            //    Intent intent, String resolvedType, IIntentReceiver resultTo,
            //    int resultCode, String resultData, Bundle resultExtras,
            //    String[] requiredPermissions, String[] excludedPermissions, int appOp, Bundle bOptions,
            //    boolean serialized, boolean sticky, int userId)
            intentArgIndex = 2;
            broadcastMethod = ActivityManagerServiceClass.getDeclaredMethod("broadcastIntentWithFeature",
                    IApplicationThreadClass, String.class,
                    Intent.class, String.class, IIntentReceiverClass,
                    int.class, String.class, Bundle.class,
                    String[].class, String[].class, int.class, Bundle.class,
                    boolean.class, boolean.class, int.class);
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            // int broadcastIntentWithFeature(IApplicationThread caller, String callingFeatureId,
            //    Intent intent, String resolvedType, IIntentReceiver resultTo,
            //    int resultCode, String resultData, Bundle resultExtras,
            //    String[] requiredPermissions, int appOp, Bundle bOptions,
            //    boolean serialized, boolean sticky, int userId)
            intentArgIndex = 2;
            broadcastMethod = ActivityManagerServiceClass.getDeclaredMethod("broadcastIntentWithFeature",
                    IApplicationThreadClass, String.class,
                    Intent.class, String.class, IIntentReceiverClass,
                    int.class, String.class, Bundle.class,
                    String[].class, int.class, Bundle.class,
                    boolean.class, boolean.class, int.class);
        } else {
            // int broadcastIntent(IApplicationThread caller,
            //    Intent intent, String resolvedType, IIntentReceiver resultTo,
            //    int resultCode, String resultData, Bundle resultExtras,
            //    String[] requiredPermissions, int appOp, Bundle bOptions,
            //    boolean serialized, boolean sticky, int userId)
            intentArgIndex = 1;
            broadcastMethod = ActivityManagerServiceClass.getDeclaredMethod("broadcastIntent",
                    IApplicationThreadClass,
                    Intent.class, String.class, IIntentReceiverClass,
                    int.class, String.class, Bundle.class,
                    String[].class, int.class, Bundle.class,
                    boolean.class, boolean.class, int.class);
        }
        hook(broadcastMethod).intercept(chain -> {
            if (chain.getArg(intentArgIndex) instanceof Intent intent) {
                if (ACTION_REMOTE_INTENT.equals(intent.getAction())
                        && getInvoker(getRecordMethod).invoke(chain.getThisObject(), chain.getArg(0)) instanceof Object app
                        && infoField.get(app) instanceof ApplicationInfo info
                        && GMS_PACKAGE_NAME.equals(info.packageName)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            && intent.getPackage() instanceof String packageName
                            && mContextField.get(chain.getThisObject()) instanceof Context mContext) {
                        getPowerExemptionManager(mContext).addToTemporaryAllowList(
                                packageName, 102 /* PowerExemptionManager.REASON_PUSH_MESSAGING_OVER_QUOTA */,
                                "GOOGLE_C2DM", 2000);
                    }
                    if ((intent.getFlags() & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) == 0) {
                        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    }
                }
            }
            return chain.proceed();
        });
        deoptimize(broadcastMethod);
    }
}
