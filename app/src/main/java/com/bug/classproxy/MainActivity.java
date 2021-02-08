package com.bug.classproxy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.bug.proxy.Proxy;
import com.bug.proxy.ProxyCallback;

import java.lang.reflect.Field;

public class MainActivity extends Activity {

    @RequiresApi(api = Build.VERSION_CODES.P)
    @SuppressLint("PrivateApi")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        try {
            Class<?> cls = Class.forName("android.app.ActivityThread");
            Object invoke = cls.getDeclaredMethod("currentActivityThread", new Class[0]).invoke(null);
            Field declaredField = cls.getDeclaredField("sPackageManager");
            declaredField.setAccessible(true);
            Object obj = declaredField.get(invoke);
            Class<?> cls2 = Class.forName("android.content.pm.IPackageManager");

            Proxy proxy = new Proxy();
            proxy.setRawObject(obj);
            proxy.setInterfaces(cls2);
            proxy.setCallback(new ProxyCallback.Hook() {
                @Override
                protected void beforeHookedMethod(final Param param) {
                }

                @Override
                protected void afterHookedMethod(final Param param) {
                    if (param.method.getName().equals("getPackageInfo")) {
                        PackageInfo packageInfo = (PackageInfo) param.getResult();
                        packageInfo.signatures = new Signature[]{new Signature("") {
                            @NonNull
                            @Override
                            public String toString() {
                                return "sign_info";
                            }
                        }};
                    }
                }
            });
            Object object = proxy.create();

            declaredField.set(invoke, object);
            PackageManager packageManager = getPackageManager();
            Field declaredField2 = packageManager.getClass().getDeclaredField("mPM");
            declaredField2.setAccessible(true);
            declaredField2.set(packageManager, object);

            @SuppressLint("WrongConstant")
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            Toast.makeText(this, packageInfo.signatures[0].toString(), Toast.LENGTH_LONG).show();

            proxy = new Proxy();
            proxy.setInterfaces(Test.class);
            ProxyCallback proxyCallback = (proxyObject, method, args) -> {
                if ("test".equals(method.getName())) {
                    return "call interface method!\n" + args[0];
                }
                return null;
            };
            proxy.setCallback(proxyCallback);
            Test test = proxy.create();
            Toast.makeText(this, test.test("arg", 0), Toast.LENGTH_LONG).show();

            proxy = new Proxy();
            proxy.setSuperClass(Test2.class);
            proxy.setCallback(proxyCallback);
            Test2 test2 = proxy.create();
            Toast.makeText(this, test2.test("arg2", 0), Toast.LENGTH_LONG).show();


            proxy = new Proxy();
            proxy.setSuperClass(Player.class);
            Player player = new Player("张三");
            proxy.setRawObject(player);
            proxy.setCallback(new ProxyCallback.Hook() {
                @Override
                protected void afterHookedMethod(Param param) throws Throwable {
                    super.afterHookedMethod(param);
                    param.setResult(param.getResult() + "他老爹");
                }
            });
            Player p = proxy.create();
            Toast.makeText(this, p.getName(), Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            e.printStackTrace();
            Log.e("BugHook", Log.getStackTraceString(e));
        }
    }

    public interface Test {
        String test(String string, int i);
    }

    public abstract static class Test2 {
        public abstract String test(String string, int i);
    }

    public class Player {
        private String name;

        public Player(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }
}