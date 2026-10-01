package com.neoruaa.geminihook;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.error.XposedFrameworkError;

public final class GeminiProHook extends XposedModule {
    private static final String TAG = "GeminiProHook";
    private static final String HOST = "com.google.android.googlequicksearchbox";
    private static final String CACHE = "com.neoruaa.geminihook.semantic-locator";
    private boolean installed;

    @Override
    @SuppressLint("DiscouragedPrivateApi") // Xposed observes the host's application lifecycle.
    public void onPackageReady(PackageReadyParam param) {
        if (!HOST.equals(param.getPackageName()) || !param.isFirstPackage()) return;
        try {
            hook(Application.class.getDeclaredMethod("attach", Context.class))
                    .setId("gemini-pro-attach")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Context context = (Context) chain.getArg(0);
                        if (HOST.equals(context.getPackageName())) {
                            install(context, param.getClassLoader());
                        }
                        return result;
                    });
        } catch (ReflectiveOperationException | LinkageError | XposedFrameworkError error) {
            log(Log.ERROR, TAG, "Cannot register application callback", error);
        }
    }

    private synchronized void install(Context context, ClassLoader loader) {
        if (installed) return;
        installed = true;
        try {
            ApplicationInfo info = context.getApplicationInfo();
            List<File> apks = new ArrayList<>();
            apks.add(new File(info.sourceDir));
            if (info.splitSourceDirs != null) {
                for (String path : info.splitSourceDirs) apks.add(new File(path));
            }
            int title = context.getResources().getIdentifier(
                    DexMenuLocator.FLOATY_TITLE_RESOURCE, "string", HOST);
            SharedPreferences cache = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE);
            String fingerprint = fingerprint(context, apks, title);
            DexMenuLocator.Result targets = readCache(cache, fingerprint);
            if (targets != null) {
                try {
                    validate(targets, loader);
                } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError error) {
                    log(Log.WARN, TAG, "Discarding invalid semantic cache", error);
                    targets = null;
                }
            }
            if (targets == null) {
                targets = DexMenuLocator.locate(apks, title);
                validate(targets, loader);
                writeCache(cache, fingerprint, targets);
            }
            for (DexMenuLocator.Target target : targets.chat) {
                Constructor<?> constructor = target.resolve(loader);
                // Keep immutable state consistent for Compose equality/hashCode.
                hook(constructor).setId("gemini-pro-chat-menu")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object[] arguments = chain.getArgs().toArray();
                            MenuPolicy.enableChatItem(arguments, target.visibilityArgument);
                            return chain.proceed(arguments);
                        });
            }
            log(Log.INFO, TAG, "Semantic chat hooks installed: " + targets.chat.size());
            for (DexMenuLocator.Target target : targets.floaty) {
                hook(target.resolve(loader)).setId("gemini-pro-floaty-menu")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object[] arguments = chain.getArgs().toArray();
                            MenuPolicy.enableFloatyItem(arguments, target.titleArgument,
                                    target.visibilityArgument, title);
                            return chain.proceed(arguments);
                        });
            }
            log(targets.floaty.isEmpty() ? Log.WARN : Log.INFO, TAG,
                    "Semantic floaty hooks installed: " + targets.floaty.size());
        } catch (Exception | LinkageError | XposedFrameworkError error) {
            log(Log.ERROR, TAG, "Semantic menu lookup/hook failed; no guessed targets used", error);
        }
    }

    private void validate(DexMenuLocator.Result targets, ClassLoader loader)
            throws ReflectiveOperationException, IOException {
        targets.validate();
        for (DexMenuLocator.Target target : targets.chat) validateTarget(target, loader, false);
        for (Iterator<DexMenuLocator.Target> iterator = targets.floaty.iterator(); iterator.hasNext();) {
            try {
                validateTarget(iterator.next(), loader, true);
            } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError error) {
                iterator.remove();
                log(Log.WARN, TAG, "Ignoring unavailable optional floaty target", error);
            }
        }
    }

    private static void validateTarget(DexMenuLocator.Target target, ClassLoader loader, boolean floaty)
            throws ReflectiveOperationException, IOException {
        if (target.visibilityArgument < 0 || target.visibilityArgument >= target.parameters.size()
                || !target.parameters.get(target.visibilityArgument).equals("Z")
                || (!floaty && target.titleArgument != -1)
                || (floaty && (target.titleArgument < 0
                || target.titleArgument >= target.parameters.size()
                || !target.parameters.get(target.titleArgument).equals("I")))) {
            throw new IOException("Invalid discovered constructor arguments");
        }
        Constructor<?> constructor = target.resolve(loader);
        Field field = constructor.getDeclaringClass().getDeclaredField(target.field);
        if (field.getType() != boolean.class || !Modifier.isFinal(field.getModifiers())
                || Modifier.isStatic(field.getModifiers())) {
            throw new NoSuchFieldException("Discovered visibility is not a final instance boolean");
        }
    }

    private static String fingerprint(Context context, List<File> apks, int title) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(HOST, 0);
        // The version is a cache key only, never a compatibility allowlist.
        StringBuilder stamp = new StringBuilder("semantic-v2:")
                .append(info.getLongVersionCode()).append(':').append(info.lastUpdateTime)
                .append(':').append(title);
        for (File apk : apks) {
            stamp.append(':').append(apk.getAbsolutePath()).append(':').append(apk.length())
                    .append(':').append(apk.lastModified());
            try (ZipFile zip = new ZipFile(apk)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.getName().matches("classes[0-9]*\\.dex")) {
                        stamp.append(':').append(entry.getName()).append(':').append(entry.getCrc());
                    }
                }
            }
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(stamp.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16))
                    .append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }

    private DexMenuLocator.Result readCache(SharedPreferences cache, String fingerprint) {
        if (!fingerprint.equals(cache.getString("fingerprint", ""))) return null;
        try {
            JSONObject json = new JSONObject(cache.getString("targets", ""));
            DexMenuLocator.Result targets = new DexMenuLocator.Result();
            readTargets(json.getJSONArray("chat"), targets.chat);
            readTargets(json.getJSONArray("floaty"), targets.floaty);
            targets.validate();
            return targets;
        } catch (Exception error) {
            log(Log.WARN, TAG, "Ignoring unreadable semantic cache", error);
            return null;
        }
    }

    private static void readTargets(JSONArray entries, List<DexMenuLocator.Target> targets)
            throws Exception {
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            JSONArray types = entry.getJSONArray("parameters");
            List<String> parameters = new ArrayList<>();
            for (int j = 0; j < types.length(); j++) parameters.add(types.getString(j));
            targets.add(new DexMenuLocator.Target(entry.getString("owner"), parameters,
                    entry.getString("field"), entry.getInt("visibility"), entry.getInt("title")));
        }
    }

    private void writeCache(SharedPreferences cache, String fingerprint, DexMenuLocator.Result targets) {
        try {
            JSONObject json = new JSONObject().put("chat", encodeTargets(targets.chat))
                    .put("floaty", encodeTargets(targets.floaty));
            cache.edit().putString("targets", json.toString())
                    .putString("fingerprint", fingerprint).apply();
        } catch (Exception error) {
            log(Log.WARN, TAG, "Cannot cache semantic targets", error);
        }
    }

    private static JSONArray encodeTargets(List<DexMenuLocator.Target> targets) throws Exception {
        JSONArray entries = new JSONArray();
        for (DexMenuLocator.Target target : targets) {
            entries.put(new JSONObject().put("owner", target.owner).put("field", target.field)
                    .put("parameters", new JSONArray(target.parameters))
                    .put("visibility", target.visibilityArgument).put("title", target.titleArgument));
        }
        return entries;
    }
}
