package com.semilocalmusicplayer.app;





import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;

@CapacitorPlugin(name = "FolderPicker")
public class FolderPickerPlugin extends Plugin {

    private static final String TAG = "FolderPicker";
    private final ExecutorService executor =
        Executors.newSingleThreadExecutor();
    private static final int MAX_DEPTH = 40;
    private static final int MAX_FILES = 20000;

    @PluginMethod
    public void pickFolder(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);

        startActivityForResult(call, intent, "folderPickerResult");
    }

    @ActivityCallback
    private void folderPickerResult(PluginCall call, ActivityResult result) {
        if (call == null) return;

        if (result == null || result.getResultCode() != Activity.RESULT_OK
                || result.getData() == null
                || result.getData().getData() == null) {
            call.reject("폴더 선택이 취소되었습니다.");
            return;
        }

        Uri treeUri = result.getData().getData();
        int grantedFlags = result.getData().getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        int readPermission = grantedFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (readPermission == 0) {
            call.reject("선택한 폴더의 읽기 권한을 받지 못했습니다. 폴더를 다시 선택하세요.");
            return;
        }

        try {
            getContext().getContentResolver()
                    .takePersistableUriPermission(treeUri, readPermission);
        } catch (SecurityException | IllegalArgumentException e) {
            Log.e(TAG, "영구 URI 권한을 저장하지 못했습니다.", e);
            call.reject("폴더 접근 권한을 유지하지 못했습니다.", e);
            return;
        }

        DocumentFile root = DocumentFile.fromTreeUri(getContext(), treeUri);
        if (root == null || !root.canRead()) {
            call.reject("선택한 폴더를 읽을 수 없습니다.");
            return;
        }

        JSArray files = new JSArray();

        try {
            String rootName = root.getName();
            if (rootName == null || rootName.trim().isEmpty()) {
                rootName = "선택한 폴더";
            }

            scanFolder(root, "", rootName, files, 0);

            JSObject response = new JSObject();
            response.put("treeUri", treeUri.toString());
            response.put("folderName", rootName);
            response.put("files", files);
            call.resolve(response);

        } catch (Exception e) {
            Log.e(TAG, "폴더 탐색 실패", e);
            call.reject("폴더를 탐색하지 못했습니다: " + e.getMessage(), e);
        }
    }

    private void scanFolder(
            DocumentFile directory,
            String relativePath,
            String rootName,
            JSArray output,
            int depth
    ) throws Exception {
        if (depth > MAX_DEPTH || output.length() >= MAX_FILES) return;

        DocumentFile[] children = directory.listFiles();

        for (DocumentFile child : children) {
            if (output.length() >= MAX_FILES) return;

            String name = child.getName();
            if (name == null || name.isEmpty()) continue;

            if (child.isDirectory()) {
                String nextPath = relativePath.isEmpty()
                        ? name
                        : relativePath + "/" + name;

                scanFolder(child, nextPath, rootName, output, depth + 1);
                continue;
            }

            if (!child.isFile() || !isAudioFile(name)) continue;

            String parent = relativePath.isEmpty()
                    ? rootName
                    : relativePath;

            JSObject item = new JSObject();
            item.put("name", name);
            item.put("uri", child.getUri().toString());
            item.put("parentFolder", parent);
            output.put(item);
        }
    }

    private boolean isAudioFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3")
                || lower.endsWith(".wav")
                || lower.endsWith(".ogg")
                || lower.endsWith(".m4a")
                || lower.endsWith(".aac")
                || lower.endsWith(".opus")
                || lower.endsWith(".flac");
    }

    /*
     * 선택한 음악 하나만 앱 캐시에 복사한다.
     * 반환되는 절대 파일 경로는 JS에서 Capacitor.convertFileSrc()로 변환한다.
     */
    @PluginMethod
    public void prepareAudio(PluginCall call) {
        String uriString = call.getString("uri");
        String name = call.getString("name", "audio.mp3");

        if (uriString == null || uriString.trim().isEmpty()) {
            call.reject("음악 파일 URI가 없습니다.");
            return;
        }

        executor.execute(() -> {
            try {
                Uri uri = Uri.parse(uriString);

                File cacheDir = new File(getContext().getCacheDir(), "music-cache");
                if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                    throw new IOException("캐시 폴더를 만들 수 없습니다.");
                }

                String key = sha256(uriString);
                String extension = getSafeExtension(name);
                File destination = new File(cacheDir, key + extension);

                if (!destination.exists() || destination.length() == 0) {
                    File temporary = new File(cacheDir, key + ".tmp");

                    try (InputStream input = getContext()
                            .getContentResolver().openInputStream(uri);
                         FileOutputStream output = new FileOutputStream(temporary)) {

                        if (input == null) {
                            throw new IOException("파일을 열 수 없습니다.");
                        }

                        byte[] buffer = new byte[64 * 1024];
                        int count;

                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                        }

                        output.flush();
                    }

                    if (destination.exists()) destination.delete();

                    if (!temporary.renameTo(destination)) {
                        copyFile(temporary, destination);
                        temporary.delete();
                    }
                }

                JSObject response = new JSObject();
                response.put("path", destination.getAbsolutePath());
                call.resolve(response);

            } catch (Exception e) {
                Log.e(TAG, "음악 파일 준비 실패", e);
                call.reject("음악 파일을 읽지 못했습니다. 폴더 권한을 다시 확인하세요.", e);
            }
        });
    }

    private void copyFile(File source, File destination) throws IOException {
        try (InputStream input = new java.io.FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int count;

            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
    }

    private String getSafeExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return ".audio";

        String ext = name.substring(dot).toLowerCase(Locale.ROOT);
        if (!ext.matches("\\.[a-z0-9]{1,8}")) return ".audio";

        return ext;
    }

    private String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(value.getBytes("UTF-8"));
        StringBuilder result = new StringBuilder();

        for (byte b : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }

        return result.toString();
    }
}
