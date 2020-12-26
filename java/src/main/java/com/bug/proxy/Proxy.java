package com.bug.proxy;

import android.annotation.SuppressLint;
import android.os.Build;

import com.bug.dexmaker.dx.Code;
import com.bug.dexmaker.dx.Comparison;
import com.bug.dexmaker.dx.DexMaker;
import com.bug.dexmaker.dx.FieldId;
import com.bug.dexmaker.dx.Label;
import com.bug.dexmaker.dx.Local;
import com.bug.dexmaker.dx.MethodId;
import com.bug.dexmaker.dx.TypeId;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Random;

import dalvik.system.DexClassLoader;
import dalvik.system.InMemoryDexClassLoader;

public class Proxy {
    private static File path;

    static {
        //以下是初始化缓存路径
        try {
            @SuppressLint("PrivateApi")
            Class<?> clazz = Class.forName("android.app.ActivityThread");
            Field field = clazz.getDeclaredField("sCurrentActivityThread");
            field.setAccessible(true);
            Object ActivityThread = field.get(null);
            field = clazz.getDeclaredField("mBoundApplication");
            field.setAccessible(true);
            Object mBoundApplication = field.get(ActivityThread);
            field = mBoundApplication.getClass().getDeclaredField("info");
            field.setAccessible(true);
            Object info = field.get(mBoundApplication);
            field = info.getClass().getDeclaredField("mDataDir");
            field.setAccessible(true);
            String DataDir = (String) field.get(info);
            clazz = Class.forName("android.os.Process");
            Method method = clazz.getDeclaredMethod("myPid");
            method.setAccessible(true);
            path = new File(DataDir, "BugProxy_" + method.invoke(null));
        } catch (Throwable ignored) {
        }
    }

    private Class<?> SuperClass = Object.class;
    private Class<?>[] interfaces = null;
    private ProxyCallback callback;
    private Object rawObject;

    private static void copyData(Object raw, Object src) {
        try {
            Class<?> clazz = raw.getClass();
            ArrayList<Field> fs = new ArrayList<>();
            for (Field f : getDeclaredFields(clazz)) {
                if (!fs.contains(f)) {
                    fs.add(f);
                }
            }
            for (Field f : getFields(clazz)) {
                if (!fs.contains(f)) {
                    fs.add(f);
                }
            }
            Class<?> c = clazz;
            while (true) {
                Class<?> cc = c.getSuperclass();
                if (cc == null || cc == Object.class || cc.equals(c)) break;
                c = cc;
                for (Field f : getDeclaredFields(c)) {
                    if (!fs.contains(f)) {
                        fs.add(f);
                    }
                }
                for (Field f : getFields(c)) {
                    if (!fs.contains(f)) {
                        fs.add(f);
                    }
                }
            }
            for (Field fie : fs) {
                fie.setAccessible(true);
                fie.set(src, fie.get(raw));
            }
        } catch (Throwable ignored) {
        }
    }

    public static Object callSuper(Object thisObject, Method method, Object[] args) {
        try {
            if (Modifier.isInterface(method.getModifiers()) || Modifier.isAbstract(method.getModifiers())) {
                Field raw_Object = thisObject.getClass().getField("raw_Object");
                return method.invoke(raw_Object.get(thisObject), args);
            }
            Method declaredMethod = thisObject.getClass().getDeclaredMethod(method.getName() + "_Super", method.getParameterTypes());
            declaredMethod.setAccessible(true);
            return declaredMethod.invoke(thisObject, args);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return null;
    }

    private static boolean contains(ArrayList<Method> array, Method method) {
        for (Method met : array) {
            if (met.getName().equals(method.getName()) && equals(met.getParameterTypes(), method.getParameterTypes())) {
                return true;
            }
        }
        return false;
    }

    private static boolean equals(Class<?>[] a, Class<?>[] b) {
        boolean eq = a.length == b.length;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            eq &= a[i].equals(b[i]);
        }
        return eq;
    }

    private static void deleteFile(File file) {
        if (!file.exists())
            return;
        if (file.isFile()) {
            file.delete();
        } else if (file.isDirectory()) {
            File[] listFiles = file.listFiles();
            if (listFiles != null) {
                for (File f : listFiles) {
                    deleteFile(f);
                }
            }
            file.delete();
        }
    }

    public Proxy setSuperClass(Class<?> SuperClass) {
        this.SuperClass = SuperClass;
        return this;
    }

    public Proxy setInterfaces(Class<?>... interfaces) {
        this.interfaces = interfaces;
        return this;
    }

    public Proxy setCallback(ProxyCallback callback) {
        this.callback = callback;
        return this;
    }

    public Proxy setRawObject(Object rawObject) {
        this.rawObject = rawObject;
        return this;
    }

    public <T> T create() throws Throwable {
        if (path == null) {
            throw new RuntimeException("tmp dir no init.");
        }
        if (SuperClass != null && SuperClass != Object.class) {
            if (Modifier.isFinal(SuperClass.getModifiers())) {
                Field field = Class.class.getDeclaredField("accessFlags");
                field.setAccessible(true);
                int accessFlags = field.getInt(SuperClass);
                accessFlags &= ~16;
                field.setInt(SuperClass, accessFlags);
            }
        }
        String ClassName = (SuperClass == Object.class ? (random(10, false)) : SuperClass.getName()) + ";";
        String source = ClassName.replaceAll(".+?/(?=[^/]+$)|\\$.+$", "");
        //path = new File("/mnt/sdcard/apk/tmp");
        //父类
        TypeId<?> superClass = TypeId.get(SuperClass);
        //继承类
        TypeId<Object> newClass = TypeId.get("L" + ClassName.substring(0, ClassName.length() - 1).replace(".", "/") + "$Proxy;");
        //所有接口
        TypeId<?>[] interfaces = new TypeId<?>[0];
        if (this.interfaces != null) {
            interfaces = new TypeId<?>[this.interfaces.length];
            int index = 0;
            while (index < interfaces.length) {
                interfaces[index] = TypeId.get(this.interfaces[index++]);
            }
        }
        DexMaker dexMaker = new DexMaker();
        //增加类定义
        dexMaker.declare(newClass, source, Modifier.PUBLIC, superClass, interfaces);
        //增加回调接口对象
        TypeId<ProxyCallback> callback = TypeId.get(ProxyCallback.class);
        FieldId<Object, ProxyCallback> callback_field = newClass.getField(callback, "callback");
        dexMaker.declare(callback_field, Modifier.PRIVATE, null);
        TypeId<Object> raw = TypeId.get(Object.class);
        FieldId<Object, Object> raw_field = newClass.getField(raw, "raw_Object");
        dexMaker.declare(raw_field, Modifier.PUBLIC, null);
        //增加无参数构造函数
        Code code = dexMaker.declare(newClass.getConstructor(), Modifier.PUBLIC);
        code.invokeDirect(TypeId.OBJECT.getConstructor(), null, code.getThis(newClass));
        code.returnVoid();
        //增加设置回调方法
        MethodId<?, Void> setCallback = newClass.getMethod(TypeId.VOID, "setCallback", callback);
        code = dexMaker.declare(setCallback, Modifier.PUBLIC);
        code.iput(callback_field, code.getThis(newClass), code.getParameter(0, callback));
        code.returnVoid();
        //获取所有需要动态代理的方法
        Method[] methods = getMethods();
        for (Method method : methods) {
            //方法属性
            int modifiers = method.getModifiers();
            //忽略Final方法
            if (Modifier.isFinal(modifiers)) {
                continue;
            }
            //方法名称
            String name = method.getName();
            //方法参数类型
            TypeId<?>[] parameterTypes;
            Class<?>[] _parameterTypes = method.getParameterTypes();
            {
                parameterTypes = new TypeId<?>[_parameterTypes.length];
                int index = 0;
                while (index < parameterTypes.length) {
                    parameterTypes[index] = TypeId.get(_parameterTypes[index++]);
                }
            }
            //返回类型
            TypeId<?> returnType = TypeId.get(method.getReturnType());
            //调用原方法
            MethodId<Object, ?> invokeSuper = newClass.getMethod(returnType, name + "_Super", parameterTypes);
            code = dexMaker.declare(invokeSuper, Modifier.PUBLIC);
            Local<?>[] args = new Local<?>[parameterTypes.length];
            for (int i = 0; i < args.length; i++) {
                args[i] = code.getParameter(i, parameterTypes[i]);
            }
            TypeId<?> resultType = getType(code, method.getReturnType());
            Local<?> result = returnType == TypeId.VOID ? null : code.newLocal(returnType);
            code.invokeSuper((MethodId<Object, Object>) TypeId.get(method.getDeclaringClass()).getMethod(returnType, name, parameterTypes), (Local<Object>) result, code.getThis(newClass), args);
            if (returnType == TypeId.VOID) {
                code.returnVoid();
            } else {
                code.returnValue(result);
            }
            //代理方法
            MethodId<?, ?> newMethod = newClass.getMethod(returnType, name, parameterTypes);
            //继承属性
            int flag = 0;
            if (Modifier.isPublic(modifiers)) {
                flag |= Modifier.PUBLIC;
            }
            if (Modifier.isPrivate(modifiers)) {
                flag |= Modifier.PRIVATE;
            }
            if (Modifier.isProtected(modifiers)) {
                flag |= Modifier.PROTECTED;
            }
            if (Modifier.isSynchronized(modifiers)) {
                flag |= Modifier.SYNCHRONIZED;
            }
            if (Modifier.isVolatile(modifiers)) {
                flag |= Modifier.VOLATILE;
            }
            if (Modifier.isStrict(modifiers)) {
                flag |= Modifier.STRICT;
            }
            code = dexMaker.declare(newMethod, flag);
            //定义寄存器
            Local<ProxyCallback> callbackObj = code.newLocal(callback);
            Local<Integer> length = code.newLocal(TypeId.INT);
            Local<Class[]> allClass = code.newLocal(TypeId.get(Class[].class));
            Local<Object[]> allArgs = code.newLocal(TypeId.get(Object[].class));
            Local<Integer> index = code.newLocal(TypeId.INT);
            Local<?> tmpObj = code.newLocal(TypeId.OBJECT);
            Local<Class> mClass = code.newLocal(TypeId.get(Class.class));
            Local<String> methodName = code.newLocal(TypeId.STRING);
            result = returnType == TypeId.VOID ? null : code.newLocal(returnType);
            args = new Local<?>[parameterTypes.length];
            for (int i = 0; i < args.length; i++) {
                args[i] = code.getParameter(i, parameterTypes[i]);
            }
            //获取回调对象
            code.iget(callback_field, callbackObj, code.getThis(newClass));
            Label lable = new Label();
            Label lable2 = new Label();
            //如果回调为null，跳转lable
            code.compareZ(Comparison.EQ, lable, callbackObj);
            //否则
            //定义参数数量
            code.loadConstant(length, parameterTypes.length);
            //定义参数类型和参数
            code.newArray(allClass, length);
            code.newArray(allArgs, length);
            //向数组添加类型
            for (int i = 0; i < parameterTypes.length; i++) {
                code.loadConstant(index, i);
                code.loadConstant((Local<Object>) tmpObj, parameterTypes[i]);
                code.aput(allClass, index, tmpObj);
            }
            //向数组添加参数
            for (int i = 0; i < parameterTypes.length; i++) {
                if (_parameterTypes[i].isPrimitive()) {
                    //基本类型需要包装
                    Class<?> type = _parameterTypes[i];
                    if (type == int.class) {
                        code.newInstance((Local<Integer>) tmpObj, TypeId.get(Integer.class).getConstructor(TypeId.INT), code.getParameter(i, parameterTypes[i]));
                    } else if (type == byte.class) {
                        code.newInstance((Local<Byte>) tmpObj, TypeId.get(Byte.class).getConstructor(TypeId.BYTE), code.getParameter(i, parameterTypes[i]));
                    } else if (type == short.class) {
                        code.newInstance((Local<Short>) tmpObj, TypeId.get(Short.class).getConstructor(TypeId.SHORT), code.getParameter(i, parameterTypes[i]));
                    } else if (type == long.class) {
                        code.newInstance((Local<Long>) tmpObj, TypeId.get(Long.class).getConstructor(TypeId.LONG), code.getParameter(i, parameterTypes[i]));
                    } else if (type == float.class) {
                        code.newInstance((Local<Float>) tmpObj, TypeId.get(Float.class).getConstructor(TypeId.FLOAT), code.getParameter(i, parameterTypes[i]));
                    } else if (type == char.class) {
                        code.newInstance((Local<Character>) tmpObj, TypeId.get(Character.class).getConstructor(TypeId.CHAR), code.getParameter(i, parameterTypes[i]));
                    } else if (type == double.class) {
                        code.newInstance((Local<Double>) tmpObj, TypeId.get(Double.class).getConstructor(TypeId.DOUBLE), code.getParameter(i, parameterTypes[i]));
                    } else if (type == boolean.class) {
                        code.newInstance((Local<Boolean>) tmpObj, TypeId.get(Boolean.class).getConstructor(TypeId.BOOLEAN), code.getParameter(i, parameterTypes[i]));
                    }
                } else {
                    //移动第i个参数到缓存寄存器
                    code.move((Local<Object>) tmpObj, (Local<Object>) code.getParameter(i, parameterTypes[i]));
                }
                //初始化数组下标
                code.loadConstant(index, i);
                //强制转换缓存对像为Object
                code.castType(tmpObj, TypeId.OBJECT);
                //写入参数
                code.aput(allArgs, index, tmpObj);
            }
            //方法名
            code.loadConstant(methodName, method.getName());
            //需要代理的方法所在类
            code.loadConstant(mClass, method.getDeclaringClass());
            //通过类对象获取原方法对象
            MethodId<Class, Method> getDeclaredMethod = TypeId.get(Class.class).getMethod(TypeId.get(Method.class), "getDeclaredMethod", TypeId.get(String.class), TypeId.get(Class[].class));
            //执行方法将返回值存入缓存寄存器
            code.invokeVirtual(getDeclaredMethod, (Local<? super Method>) tmpObj, mClass, methodName, allClass);
            //回调方法
            MethodId<ProxyCallback, Object> call = callback.getMethod(TypeId.OBJECT, "call", TypeId.OBJECT, TypeId.get(Method.class), TypeId.get(Object[].class));
            //调用接口方法并存入返回值，同上
            code.invokeInterface(call, (Local<? super Object>) tmpObj, callbackObj, code.getThis(newClass), tmpObj, allArgs);
            //通过返回类型做不同处理
            Class<?> type = method.getReturnType();
            if (type != void.class) {
                //基本类型时强转对应包装类，并获取值存入返回值寄存器
                code.castType(tmpObj, resultType);
                if (type == int.class) {
                    code.invokeVirtual((MethodId<Object, Integer>) ((TypeId<?>) TypeId.get(Integer.class)).getMethod(TypeId.INT, "intValue"), (Local<Integer>) result, tmpObj);
                } else if (type == byte.class) {
                    code.invokeVirtual((MethodId<Object, Byte>) ((TypeId<?>) TypeId.get(Byte.class)).getMethod(TypeId.BYTE, "byteValue"), (Local<Byte>) result, tmpObj);
                } else if (type == short.class) {
                    code.invokeVirtual((MethodId<Object, Short>) ((TypeId<?>) TypeId.get(Short.class)).getMethod(TypeId.SHORT, "shortValue"), (Local<Short>) result, tmpObj);
                } else if (type == long.class) {
                    code.invokeVirtual((MethodId<Object, Long>) ((TypeId<?>) TypeId.get(Long.class)).getMethod(TypeId.LONG, "longValue"), (Local<Long>) result, tmpObj);
                } else if (type == float.class) {
                    code.invokeVirtual((MethodId<Object, Float>) ((TypeId<?>) TypeId.get(Float.class)).getMethod(TypeId.FLOAT, "floatValue"), (Local<Float>) result, tmpObj);
                } else if (type == char.class) {
                    code.invokeVirtual((MethodId<Object, Character>) ((TypeId<?>) TypeId.get(Character.class)).getMethod(TypeId.CHAR, "charValue"), (Local<Character>) result, tmpObj);
                } else if (type == double.class) {
                    code.invokeVirtual((MethodId<Object, Double>) ((TypeId<?>) TypeId.get(Double.class)).getMethod(TypeId.DOUBLE, "doubleValue"), (Local<Double>) result, tmpObj);
                } else if (type == boolean.class) {
                    code.invokeVirtual((MethodId<Object, Boolean>) ((TypeId<?>) TypeId.get(Boolean.class)).getMethod(TypeId.BOOLEAN, "booleanValue"), (Local<Boolean>) result, tmpObj);
                } else {
                    //移动缓存寄存器到返回值寄存器
                    code.move((Local<Object>) result, (Local<Object>) tmpObj);
                }
            }
            //跳转lable2
            code.jump(lable2);

            //标记lable
            code.mark(lable);
            //未设置回调直接调用原方法
            code.invokeVirtual(invokeSuper, (Local<Object>) result, code.getThis(newClass), args);
            //跳转lable2
            code.jump(lable2);

            //标记lable2
            code.mark(lable2);
            if (method.getReturnType() != void.class) {
                //非void
                code.returnValue(result);
            } else {
                //void
                code.returnVoid();
            }
        }
        byte[] generate = dexMaker.generate();
        ClassLoader loader;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            loader = new InMemoryDexClassLoader(ByteBuffer.wrap(generate), new FixClassLoader(SuperClass.getClassLoader(), Proxy.class.getClassLoader()));
        } else {
            //缓存目录不存在则创建
            if (!path.exists()) {
                path.mkdirs();
            }
            //dex文件
            File dexfile = new File(path, SuperClass.getName() + ".dex");
            File cache = new File(path, "cache");
            if (!cache.exists())
                cache.mkdirs();
            //写入数据
            FileOutputStream output = new FileOutputStream(dexfile);
            output.write(generate);
            output.close();
            loader = new DexClassLoader(dexfile.getPath(), cache.getPath(), null, new FixClassLoader(SuperClass.getClassLoader(), Proxy.class.getClassLoader()));
            //加载完毕直接删除缓存目录
            deleteFile(path);
        }
        //加载代理类
        Class<?> clazz = loader.loadClass(ClassName.substring(0, ClassName.length() - 1) + "$Proxy");
        //获取之前定义好的无参数构造器
        Constructor<?> constructor = clazz.getConstructor();
        constructor.setAccessible(true);
        //生成对象
        Object newInstance = constructor.newInstance();
        //设置回调
        clazz.getMethod("setCallback", ProxyCallback.class).invoke(newInstance, this.callback);
        //原对象不为空时复制字段数据
        if (rawObject != null) {
            clazz.getField("raw_Object").set(newInstance, rawObject);
            copyData(rawObject, newInstance);
        }
        return (T) newInstance;
    }

    private TypeId<?> getType(Code code, Class<?> type) {
        TypeId<?> id;
        if (type == int.class) {
            id = TypeId.get(Integer.class);
        } else if (type == byte.class) {
            id = TypeId.get(Byte.class);
        } else if (type == short.class) {
            id = TypeId.get(Short.class);
        } else if (type == long.class) {
            id = TypeId.get(Long.class);
        } else if (type == float.class) {
            id = TypeId.get(Float.class);
        } else if (type == char.class) {
            id = TypeId.get(Character.class);
        } else if (type == double.class) {
            id = TypeId.get(Double.class);
        } else if (type == boolean.class) {
            id = TypeId.get(Boolean.class);
        } else {
            id = TypeId.get(type);
        }
        return id;
    }

    private Method[] getMethods() throws InvocationTargetException, IllegalAccessException {
        ArrayList<Method> array = new ArrayList<>();
        for (Method con : getDeclaredMethods(SuperClass)) {
            if (!contains(array, con) && !Modifier.isStatic(con.getModifiers())) {
                array.add(con);
            }
        }
        for (Method con : getMethods(SuperClass)) {
            if (!contains(array, con) && !Modifier.isStatic(con.getModifiers())) {
                array.add(con);
            }
        }
        Class<?> clazz = SuperClass;
        while (true) {
            Class<?> cc = clazz.getSuperclass();
            if (cc == null || cc.equals(clazz))
                break;
            clazz = cc;
            for (Method con : getDeclaredMethods(clazz)) {
                if (!contains(array, con) && !Modifier.isStatic(con.getModifiers())) {
                    array.add(con);
                }
            }
            for (Method con : getMethods(clazz)) {
                if (!contains(array, con) && !Modifier.isStatic(con.getModifiers())) {
                    array.add(con);
                }
            }
        }
        if (interfaces != null) {
            for (Method method : getInterfaceMethods()) {
                if (!contains(array, method)) {
                    array.add(method);
                }
            }
        }
        return array.toArray(new Method[0]);
    }

    private Method[] getInterfaceMethods() throws InvocationTargetException, IllegalAccessException {
        ArrayList<Method> array = new ArrayList<>();
        for (Class<?> interfaceClass : interfaces) {
            for (Method con : getDeclaredMethods(interfaceClass)) {
                if (!contains(array, con) && !Modifier.isStatic(con.getModifiers())) {
                    array.add(con);
                }
            }
            for (Method con : getMethods(interfaceClass)) {
                if (!contains(array, con) && !Modifier.isStatic(con.getModifiers())) {
                    array.add(con);
                }
            }
            Class<?> clazz = interfaceClass;
            while (true) {
                Class<?> cc = clazz.getSuperclass();
                if (cc == null || cc.equals(clazz))
                    break;
                clazz = cc;
                for (Method con : getDeclaredMethods(clazz)) {
                    if (!contains(array, con) && !Modifier.isStatic(con.getModifiers())) {
                        array.add(con);
                    }
                }
                for (Method con : getMethods(clazz)) {
                    if (!contains(array, con) && !Modifier.isStatic(con.getModifiers())) {
                        array.add(con);
                    }
                }
            }
        }
        return array.toArray(new Method[0]);
    }

    private static class FixClassLoader extends ClassLoader {
        private final ClassLoader mAppClassLoader;

        public FixClassLoader(ClassLoader parent, ClassLoader appClassLoader) {
            super(parent);
            mAppClassLoader = appClassLoader;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> clazz = null;
            try {
                clazz = mAppClassLoader.loadClass(name);
            } catch (ClassNotFoundException ignored) {
            }
            if (clazz == null) {
                clazz = super.loadClass(name, resolve);
            }
            if (clazz == null) {
                throw new ClassNotFoundException();
            }
            return clazz;
        }
    }

    public static String random(int length, boolean allNumbers) {
        ArrayList<Character> list = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            list.add(String.valueOf(i).charAt(0));
        }
        if (!allNumbers) {
            for (char i = 'a'; i <= 'z'; i++) {
                list.add(i);
            }
            for (char i = 'A'; i <= 'Z'; i++) {
                list.add(i);
            }
        }
        StringBuilder str = new StringBuilder();
        Random random = new Random();
        while (str.length() < length) {
            str.append(list.get(random.nextInt(list.size())));
        }
        return str.toString();
    }

    private static Method getFields;
    private static Method getDeclaredFields;
    private static Method getMethods;
    private static Method getDeclaredMethods;

    static {
        try {
            Class<?> clazz = Class.class;
            getFields = clazz.getMethod("getFields");
            getDeclaredFields = clazz.getMethod("getDeclaredFields");
            getMethods = clazz.getMethod("getMethods");
            getDeclaredMethods = clazz.getMethod("getDeclaredMethods");
        } catch (NoSuchMethodException ignored) {
        }
    }

    private static Field[] getFields(Class<?> clazz) throws InvocationTargetException, IllegalAccessException {
        return (Field[]) getFields.invoke(clazz);
    }

    private static Field[] getDeclaredFields(Class<?> clazz) throws InvocationTargetException, IllegalAccessException {
        return (Field[]) getDeclaredFields.invoke(clazz);
    }


    private static Method[] getMethods(Class<?> clazz) throws InvocationTargetException, IllegalAccessException {
        return (Method[]) getMethods.invoke(clazz);
    }

    private static Method[] getDeclaredMethods(Class<?> clazz) throws InvocationTargetException, IllegalAccessException {
        return (Method[]) getDeclaredMethods.invoke(clazz);
    }
}