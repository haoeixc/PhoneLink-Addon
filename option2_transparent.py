import re

with open('app/src/main/java/com/bypass/projection/MainHook.java', 'r', encoding='utf-8') as f:
    code = f.read()

# 1. Add variables
code = code.replace('private static final String TAG = "PhoneLinkAddon";', 'private static final String TAG = "PhoneLinkAddon";\n    private static boolean sIsBlackScreenActive = false;\n    private static Object sMiuiGxzwManagerInstance = null;')

# 2. Add Microsoft broadcast
ms_broadcast_true = '''                            param.setResult(null); // Bypass completely
                            
                            // Send broadcast to SystemUI to make fingerprint transparent
                            try {
                                android.app.Application app = android.app.AndroidAppHelper.currentApplication();
                                if (app != null) {
                                    android.content.Intent intent = new android.content.Intent("com.bypass.projection.BLACK_SCREEN_STATE");
                                    intent.putExtra("active", true);
                                    app.sendBroadcast(intent);
                                    XposedBridge.log(TAG + ": Broadcasted BLACK_SCREEN_STATE true");
                                }
                            } catch (Throwable ignored) {}'''

code = code.replace('                            param.setResult(null); // Bypass completely', ms_broadcast_true)

ms_broadcast_false = '''                    } catch (Throwable ignored) {}
                }
            });
            
            // Also hook the dismiss to reset it
            XposedHelpers.findAndHookMethod(android.app.Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    android.app.Application app = (android.app.Application) param.thisObject;
                    String className = (lpparam.packageName.equals("com.microsoft.appmanager")) ?
                            "com.microsoft.deviceintegrationservice.display.presentation.PresentationActivity" :
                            "com.microsoft.deviceintegrationservice.display.presentation.PresentationService";
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
            });'''

code = code.replace('                    } catch (Throwable ignored) {}\n                }\n            });', ms_broadcast_false)

# 3. Add SystemUI hook block at the very end before the last closing brace
systemui_block = '''
        // 3. Hook SystemUI to dynamically set Fingerprint Icon Alpha to 0
        if (lpparam.packageName.equals("com.android.systemui")) {
            XposedBridge.log(TAG + ": Initializing SystemUI transparent icon hooks...");
            
            android.content.IntentFilter filter = new android.content.IntentFilter();
            filter.addAction("com.bypass.projection.BLACK_SCREEN_STATE");
            filter.addAction(android.content.Intent.ACTION_SCREEN_OFF);
            filter.addAction(android.content.Intent.ACTION_USER_PRESENT);
            
            android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, android.content.Intent intent) {
                    String action = intent.getAction();
                    if (android.content.Intent.ACTION_SCREEN_OFF.equals(action) || android.content.Intent.ACTION_USER_PRESENT.equals(action)) {
                        sIsBlackScreenActive = false;
                    } else if ("com.bypass.projection.BLACK_SCREEN_STATE".equals(action)) {
                        sIsBlackScreenActive = intent.getBooleanExtra("active", false);
                    }
                    XposedBridge.log(TAG + ": Fingerprint transparency state updated: " + sIsBlackScreenActive);
                    
                    if (sMiuiGxzwManagerInstance != null) {
                        try {
                            XposedHelpers.callMethod(sMiuiGxzwManagerInstance, "updateGxzwState");
                        } catch (Throwable t) {}
                    }
                }
            };

            XposedHelpers.findAndHookMethod(android.app.Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    android.app.Application app = (android.app.Application) param.thisObject;
                    if (app.getPackageName().equals("com.android.systemui")) {
                        try {
                            try {
                                XposedHelpers.callMethod(app, "registerReceiver", receiver, filter, 2); // RECEIVER_EXPORTED
                            } catch (Throwable t) {
                                app.registerReceiver(receiver, filter);
                            }
                        } catch (Throwable ignored) {}
                        
                        // Hook WindowManagerGlobal to physically intercept the alpha inside SystemUI
                        try {
                            Class<?> wmgClass = XposedHelpers.findClass("android.view.WindowManagerGlobal", app.getClassLoader());
                            XC_MethodHook alphaHook = new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    android.view.ViewGroup.LayoutParams params = (android.view.ViewGroup.LayoutParams) param.args[1];
                                    if (params instanceof android.view.WindowManager.LayoutParams) {
                                        android.view.WindowManager.LayoutParams lp = (android.view.WindowManager.LayoutParams) params;
                                        String title = lp.getTitle() != null ? lp.getTitle().toString() : "";
                                        if (title.contains("MiuiGxzwIconView") || title.contains("MiuiGxzwAnimView")) {
                                            if (sIsBlackScreenActive) {
                                                lp.alpha = 0.0f; // Make it completely transparent!
                                            } else {
                                                lp.alpha = 1.0f; // Restore
                                            }
                                        }
                                    }
                                }
                            };
                            XposedHelpers.findAndHookMethod(wmgClass, "addView", android.view.View.class, android.view.ViewGroup.LayoutParams.class, android.view.Display.class, android.view.Window.class, int.class, alphaHook);
                            XposedHelpers.findAndHookMethod(wmgClass, "updateViewLayout", android.view.View.class, android.view.ViewGroup.LayoutParams.class, alphaHook);
                            XposedBridge.log(TAG + ": WindowManagerGlobal alpha hooks applied successfully.");
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": Failed to hook WindowManagerGlobal - " + t.getMessage());
                        }
                    }
                }
            });

            try {
                Class<?> gxzwClass = XposedHelpers.findClass("com.miui.keyguard.biometrics.fod.MiuiGxzwManager", lpparam.classLoader);
                XposedHelpers.findAndHookConstructor(gxzwClass, android.content.Context.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        sMiuiGxzwManagerInstance = param.thisObject;
                    }
                });
            } catch (Throwable t) {}
        }
    }
}'''

# Replace the last closing braces
code = re.sub(r'        \}\n    \}\n\}', systemui_block, code)

with open('app/src/main/java/com/bypass/projection/MainHook.java', 'w', encoding='utf-8') as f:
    f.write(code)
