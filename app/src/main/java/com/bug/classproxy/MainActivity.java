package com.bug.classproxy;

import android.annotation.SuppressLint;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import com.bug.proxy.Proxy;
import com.bug.proxy.ProxyCallback;

import java.lang.reflect.Field;

public class MainActivity extends AppCompatActivity {

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
                        packageInfo.signatures = new Signature[]{new Signature((String) null) {
                            @NonNull
                            @Override
                            public String toString() {
                                return "就这？";
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

            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            Toast.makeText(this, packageInfo.signatures[0].toString(), Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}