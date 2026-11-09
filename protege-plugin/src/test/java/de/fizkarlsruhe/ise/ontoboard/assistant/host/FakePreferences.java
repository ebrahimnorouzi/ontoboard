package de.fizkarlsruhe.ise.ontoboard.assistant.host;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.protege.editor.core.prefs.Preferences;

/** In-memory Protégé preferences, as {@code CollabSettingsStoreTest} has, with a peek at the raw values. */
public final class FakePreferences implements Preferences {

    private final Map<String, Object> values = new LinkedHashMap<String, Object>();

    /** What actually reached the store, or null. */
    public String raw(String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public Map<String, Object> all() {
        return values;
    }

    @Override
    public void clear() {
        values.clear();
    }

    @Override
    public String getString(String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    @Override
    public void putString(String key, String value) {
        values.put(key, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> getStringList(String key, List<String> fallback) {
        Object value = values.get(key);
        return value instanceof List ? (List<String>) value : fallback;
    }

    @Override
    public void putStringList(String key, List<String> value) {
        values.put(key, new ArrayList<String>(value));
    }

    @Override
    public int getInt(String key, int fallback) {
        Object value = values.get(key);
        return value instanceof Integer ? (Integer) value : fallback;
    }

    @Override
    public void putInt(String key, int value) {
        values.put(key, value);
    }

    @Override
    public float getFloat(String key, float fallback) {
        Object value = values.get(key);
        return value instanceof Float ? (Float) value : fallback;
    }

    @Override
    public void putFloat(String key, float value) {
        values.put(key, value);
    }

    @Override
    public long getLong(String key, long fallback) {
        Object value = values.get(key);
        return value instanceof Long ? (Long) value : fallback;
    }

    @Override
    public void putLong(String key, long value) {
        values.put(key, value);
    }

    @Override
    public double getDouble(String key, double fallback) {
        Object value = values.get(key);
        return value instanceof Double ? (Double) value : fallback;
    }

    @Override
    public void putDouble(String key, double value) {
        values.put(key, value);
    }

    @Override
    public boolean getBoolean(String key, boolean fallback) {
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    @Override
    public void putBoolean(String key, boolean value) {
        values.put(key, value);
    }

    @Override
    public byte[] getByteArray(String key, byte[] fallback) {
        return fallback;
    }

    @Override
    public void putByteArray(String key, byte[] value) {
        values.put(key, value);
    }

    @Override
    public List<byte[]> getByteArrayList(String key, List<byte[]> fallback) {
        return fallback;
    }

    @Override
    public void putByteArrayList(String key, List<byte[]> value) {
        values.put(key, value);
    }
}
