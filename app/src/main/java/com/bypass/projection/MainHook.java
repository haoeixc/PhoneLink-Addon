package com.bypass.projection;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.util.HashSet;
import java.util.Set;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "PhoneLinkAddon";
    private static boolean sIsBlackScreenActive = false;
    private static Object sMiuiGxzwManagerInstance = null;
    private static final String PACKAGE_ID = "com.bypass.projection";
    private static final String PREFS_NAME = "module_prefs";
    private static final String LTW_PACKAGE = "com.microsoft.appmanager";

    // Packages we care about — skip everything else immediately
    private static final Set<String> TARGET_PACKAGES = new HashSet<>();
    static {
        TARGET_PACKAGES.add("android");
        TARGET_PACKAGES.add(LTW_PACKAGE);
        TARGET_PACKAGES.add("com.microsoft.deviceintegrationservice");
        TARGET_PACKAGES.add("com.microsoftsdk.crossdeviceservicebroker");
        TARGET_PACKAGES.add("com.android.systemui");
        TARGET_PACKAGES.add(PACKAGE_ID); // Self-hook for module status
    }

    // Runtime state
    private static Object sBrokerCInstance = null;
    private static boolean sReceiverRegistered = false;
    private static boolean sIsProjectionActive = false;

    private static XSharedPreferences loadPrefs() {
        XSharedPreferences prefs = new XSharedPreferences(PACKAGE_ID, PREFS_NAME);
        prefs.makeWorldReadable();
        return prefs;
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGES.contains(lpparam.packageName)) return;

        // Self-hook to verify module is active
        if (lpparam.packageName.equals(PACKAGE_ID)) {
            try {
                XposedHelpers.findAndHookMethod(PACKAGE_ID + ".MainActivity", lpparam.classLoader,
                        "isModuleActive", XC_MethodReplacement.returnConstant(true));
            } catch (Throwable ignored) {}
            return;
        }

        final XSharedPreferences prefs = loadPrefs();
        if (!prefs.getBoolean("master_switch", true)) return;

        if (lpparam.packageName.equals("com.android.systemui")) {
            hookSystemUI(lpparam, prefs);
            return;
        }

        switch (lpparam.packageName) {
            case "android":
                hookSystemServer(lpparam, prefs);
                break;
            case "com.microsoft.appmanager":
            case "com.microsoft.deviceintegrationservice":
                hookMicrosoftApps(lpparam, prefs);
                break;
            case "com.microsoftsdk.crossdeviceservicebroker":
                hookBroker(lpparam, prefs);
                break;
        }
    }

    private void hookSystemServer(LoadPackageParam lpparam, XSharedPreferences prefs) {
        if (!prefs.getBoolean("lockscreen_bypass", true)) return;
        try {
            final Class<?> stopControllerClass = XposedHelpers.findClass(
                    "com.android.server.media.projection.MediaProjectionStopController",
                    lpparam.classLoader);

            XposedHelpers.findAndHookMethod(stopControllerClass,
                    "onKeyguardLockedStateChanged", boolean.class,
                    XC_MethodReplacement.returnConstant(null));

            XposedHelpers.findAndHookMethod(stopControllerClass,
                    "isStartForbidden",
                    "com.android.server.media.projection.MediaProjectionManagerService$MediaProjection",
                    XC_MethodReplacement.returnConstant(false));

            final Class<?> keyguardControllerClass = XposedHelpers.findClass(
                    "com.android.server.wm.KeyguardController", lpparam.classLoader);

            final XC_MethodHook displayHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof Integer) {
                        if ((Integer) param.args[0] != 0) {
                            param.setResult(false);
                        }
                    }
                }
            };

            XposedHelpers.findAndHookMethod(keyguardControllerClass, "isKeyguardLocked", int.class, displayHook);
            XposedHelpers.findAndHookMethod(keyguardControllerClass, "isKeyguardShowing", int.class, displayHook);
            XposedHelpers.findAndHookMethod(keyguardControllerClass, "isKeyguardOrAodShowing", int.class, displayHook);

        } catch (Throwable t) {}
    }

    private void hookMicrosoftApps(LoadPackageParam lpparam, XSharedPreferences prefs) {
        if (prefs.getBoolean("lockscreen_bypass", true)) {
            try {
                final Class<?> km = XposedHelpers.findClass("android.app.KeyguardManager", lpparam.classLoader);
                final XC_MethodHook falseHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(false);
                    }
                };
                XposedHelpers.findAndHookMethod(km, "isDeviceLocked", falseHook);
                XposedHelpers.findAndHookMethod(km, "isKeyguardLocked", falseHook);
                XposedHelpers.findAndHookMethod(km, "isKeyguardSecure", falseHook);
            } catch (Throwable t) {}
        }

        if (!lpparam.packageName.equals("com.microsoft.deviceintegrationservice")) return;

        try {
            if (prefs.getBoolean("black_screen_fix", true)) {
                Class<?> blackScreenClass = XposedHelpers.findClassIfExists(
                        "com.microsoft.deviceExperiences.blackscreen.a", lpparam.classLoader);
                if (blackScreenClass != null) {
                    XposedHelpers.findAndHookMethod(blackScreenClass, "a",
                            android.view.WindowManager.LayoutParams.class, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    android.view.WindowManager.LayoutParams p =
                                            (android.view.WindowManager.LayoutParams) param.args[0];
                                    p.flags |= android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                            | android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                                            | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                                            | android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
                                }
                            });
                }
            }

            if (prefs.getBoolean("bypass_dialog", true)) {
                Class<?> permClass = XposedHelpers.findClassIfExists(
                        "com.microsoft.deviceExperiences.permission.MediaProjectionPermissionActivity",
                        lpparam.classLoader);
                if (permClass != null) {
                    XposedHelpers.findAndHookMethod(permClass, "onCreate",
                            android.os.Bundle.class, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        XposedHelpers.callMethod(param.thisObject, "onPositiveButtonClick");
                                        XposedBridge.log(TAG + ": Projection dialog auto-accepted.");
                                        
                                        // Broadcast BLACK_SCREEN_STATE active
                                        android.app.Application app = android.app.AndroidAppHelper.currentApplication();
                                        if (app != null) {
                                            android.content.Intent intent = new android.content.Intent("com.bypass.projection.BLACK_SCREEN_STATE");
                                            intent.putExtra("active", true);
                                            app.sendBroadcast(intent);
                                            XposedBridge.log(TAG + ": Broadcasted BLACK_SCREEN_STATE true");
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            });
                }
            }
            
            // Broadcast BLACK_SCREEN_STATE false when presentation ends
            XposedHelpers.findAndHookMethod(android.app.Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    android.app.Application app = (android.app.Application) param.thisObject;
                    String className = "com.microsoft.deviceintegrationservice.display.presentation.PresentationService";
                    try {
                        Class<?> presentationClass = XposedHelpers.findClass(className, app.getClassLoader());
                        XposedHelpers.findAndHookMethod(presentationClass, "hidePresentation", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    android.app.Application appInst = android.app.AndroidAppHelper.currentApplication();
                                    if (appInst != null) {
                                        android.content.Intent intent = new android.content.Intent("com.bypass.projection.BLACK_SCREEN_STATE");
                                        intent.putExtra("active", false);
                                        appInst.sendBroadcast(intent);
                                        XposedBridge.log(TAG + ": Broadcasted BLACK_SCREEN_STATE false");
                                    }
                                } catch (Throwable ignored) {}
                            }
                        });
                    } catch (Throwable ignored) {}
                }
            });
            
        } catch (Throwable t) {}
    }

    private void hookBroker(LoadPackageParam lpparam, XSharedPreferences prefs) {
        try {
            if (prefs.getBoolean("black_screen_fix", true)) {
                final Class<?> layoutParamsClass = XposedHelpers.findClass(
                        "android.view.WindowManager$LayoutParams", lpparam.classLoader);

                XposedHelpers.findAndHookMethod(layoutParamsClass, "setTitle",
                        CharSequence.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                CharSequence title = (CharSequence) param.args[0];
                                if (title != null && title.toString().contains("blackscreen")) {
                                    android.view.WindowManager.LayoutParams lp =
                                            (android.view.WindowManager.LayoutParams) param.thisObject;
                                    lp.flags |= android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                            | android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                                            | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                                            | android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
                                    lp.type = 2009; // TYPE_KEYGUARD_DIALOG
                                }
                            }
                        });
            }

            final Class<?> brokerCClass = XposedHelpers.findClass(
                    "com.microsoftsdk.crossdeviceservicebroker.c", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(brokerCClass, "showBlackScreen",
                    int.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            XposedHelpers.setAdditionalInstanceField(param.thisObject, "lastArg0", param.args[0]);
                            XposedHelpers.setAdditionalInstanceField(param.thisObject, "lastArg1", param.args[1]);
                            XposedHelpers.setAdditionalInstanceField(param.thisObject, "lastArg2", param.args[2]);
                            sBrokerCInstance = param.thisObject;
                            sIsProjectionActive = true;
                        }
                    });

            XposedHelpers.findAndHookMethod(brokerCClass, "hideBlackScreen", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sIsProjectionActive = false;
                }
            });

            if (prefs.getBoolean("auto_restore", true)) {
                XposedHelpers.findAndHookMethod(android.app.Application.class, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (sReceiverRegistered) return;
                        sReceiverRegistered = true;

                        android.app.Application app = (android.app.Application) param.thisObject;
                        app.registerReceiver(new android.content.BroadcastReceiver() {
                            @Override
                            public void onReceive(android.content.Context ctx, android.content.Intent intent) {
                                if (android.content.Intent.ACTION_SCREEN_OFF.equals(intent.getAction())
                                        && sIsProjectionActive && sBrokerCInstance != null) {
                                    try {
                                        Object a0 = XposedHelpers.getAdditionalInstanceField(sBrokerCInstance, "lastArg0");
                                        Object a1 = XposedHelpers.getAdditionalInstanceField(sBrokerCInstance, "lastArg1");
                                        Object a2 = XposedHelpers.getAdditionalInstanceField(sBrokerCInstance, "lastArg2");
                                        if (a0 != null && a1 != null && a2 != null) {
                                            XposedHelpers.callMethod(sBrokerCInstance, "showBlackScreen",
                                                    (int) a0, (int) a1, (int) a2);
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }, new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF));
                    }
                });
            }
        } catch (Throwable t) {}
    }

    private void hookSystemUI(LoadPackageParam lpparam, XSharedPreferences prefs) {
        XposedHelpers.findAndHookMethod(android.app.Application.class, "onCreate", new XC_MethodHook() {
            private boolean sSystemUiChecked = false;

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (sSystemUiChecked) return;
                sSystemUiChecked = true;

                android.app.Application app = (android.app.Application) param.thisObject;

                try {
                    app.getPackageManager().getPackageInfo(LTW_PACKAGE, 0);
                } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                    return;
                }

                // 1. Existing SystemUI hooks (KeyguardDisplayManager)
                if (prefs.getBoolean("lockscreen_bypass", true)) {
                    String[] classNames = {
                            "com.android.systemui.keyguard.KeyguardDisplayManager",
                            "com.android.keyguard.KeyguardDisplayManager",
                            "com.android.systemui.statusbar.phone.KeyguardDisplayManager"
                    };

                    for (String className : classNames) {
                        Class<?> cls = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
                        if (cls == null) continue;

                        try {
                            XposedHelpers.findAndHookMethod(cls, "show",
                                    XC_MethodReplacement.returnConstant(null));
                        } catch (Throwable ignored) {}

                        try {
                            XposedHelpers.findAndHookMethod(cls, "showPresentation",
                                    android.view.Display.class,
                                    XC_MethodReplacement.returnConstant(false));
                        } catch (Throwable ignored) {}
                    }
                }
                
                // 2. Fingerprint Icon Blink Suppression (Approach C: Direct Method Interception)
                // Note: This makes the fingerprint icon completely invisible to prevent blinking,
                // while leaving the actual biometric hardware functional.
                XposedBridge.log(TAG + ": Initializing fingerprint blink suppression hooks...");
                
                // Only listen to our custom broadcast and SCREEN_ON (when user picks up phone)
                android.content.IntentFilter filter = new android.content.IntentFilter();
                filter.addAction("com.bypass.projection.BLACK_SCREEN_STATE");
                filter.addAction(android.content.Intent.ACTION_SCREEN_ON);
                
                android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                    @Override
                    public void onReceive(android.content.Context context, android.content.Intent intent) {
                        if (android.content.Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                            sIsBlackScreenActive = false;
                            XposedBridge.log(TAG + ": Screen ON detected, restoring fingerprint.");
                            if (sMiuiGxzwManagerInstance != null) {
                                try {
                                    XposedHelpers.callMethod(sMiuiGxzwManagerInstance, "updateGxzwState");
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + ": Failed to restore icon: " + t.getMessage());
                                }
                            }
                        } else if ("com.bypass.projection.BLACK_SCREEN_STATE".equals(intent.getAction())) {
                            boolean active = intent.getBooleanExtra("active", false);
                            sIsBlackScreenActive = active;
                            XposedBridge.log(TAG + ": Fingerprint blink suppression: " + (active ? "ON" : "OFF"));
                            
                            // Immediately restore the fingerprint icon if projection ended
                            if (!active && sMiuiGxzwManagerInstance != null) {
                                try {
                                    XposedBridge.log(TAG + ": Restoring fingerprint icon state immediately.");
                                    XposedHelpers.callMethod(sMiuiGxzwManagerInstance, "updateGxzwState");
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + ": Failed to restore icon: " + t.getMessage());
                                }
                            }
                        }
                    }
                };

                try {
                    try {
                        XposedHelpers.callMethod(app, "registerReceiver", receiver, filter, 2); // RECEIVER_EXPORTED
                    } catch (Throwable t) {
                        app.registerReceiver(receiver, filter);
                    }
                    XposedBridge.log(TAG + ": Broadcast receiver registered.");
                } catch (Throwable ignored) {}
                
                // Hook MiuiGxzwManager constructor to capture instance
                try {
                    Class<?> gxzwManagerClass = XposedHelpers.findClass(
                            "com.miui.keyguard.biometrics.fod.MiuiGxzwManager", lpparam.classLoader);
                    XposedHelpers.findAndHookConstructor(gxzwManagerClass, android.content.Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sMiuiGxzwManagerInstance = param.thisObject;
                            XposedBridge.log(TAG + ": MiuiGxzwManager instance captured.");
                        }
                    });
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": MiuiGxzwManager constructor hook failed: " + t.getMessage());
                }
                
                // Hook decodeBitmap in MiuiGxzwFrameAnimation to replace the fingerprint icon with a transparent one
                try {
                    Class<?> frameAnimClass = XposedHelpers.findClass(
                            "com.miui.keyguard.biometrics.fod.MiuiGxzwFrameAnimation", lpparam.classLoader);
                    
                    XposedHelpers.findAndHookMethod(frameAnimClass, "decodeBitmap",
                            Object.class, boolean.class, new XC_MethodHook() {
                                private android.graphics.Bitmap transparentBitmap = null;
                                
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    if (sIsBlackScreenActive && prefs.getBoolean("invisible_fingerprint", true)) {
                                        if (transparentBitmap == null) {
                                            transparentBitmap = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888);
                                            transparentBitmap.eraseColor(android.graphics.Color.TRANSPARENT);
                                        }
                                        param.setResult(transparentBitmap);
                                    }
                                }
                            });
                    XposedBridge.log(TAG + ": Hooked decodeBitmap()");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": Failed to hook decodeBitmap: " + t.getMessage());
                }
                
                // Clear animation cache when projection stops to ensure the transparent bitmap isn't permanently cached
                try {
                    Class<?> frameAnimClass = XposedHelpers.findClass(
                            "com.miui.keyguard.biometrics.fod.MiuiGxzwFrameAnimation", lpparam.classLoader);
                    XposedHelpers.findAndHookMethod(frameAnimClass, "stopAnimation", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!sIsBlackScreenActive) {
                                try {
                                    java.util.concurrent.ConcurrentHashMap<?, ?> cache = 
                                        (java.util.concurrent.ConcurrentHashMap<?, ?>) XposedHelpers.getObjectField(param.thisObject, "mGxzwAnimCacheBitmapHashMap");
                                    if (cache != null) {
                                        cache.clear();
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    });
                } catch (Throwable ignored) {}
                
            }
        });
    }
}
