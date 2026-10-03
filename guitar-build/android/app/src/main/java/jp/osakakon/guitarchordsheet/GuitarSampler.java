package jp.osakakon.guitarchordsheet;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GuitarSampler {
    private static final String BASE = "audio/freepats/samples/";
    private static final long LATE_LIMIT_MS = 320L;

    private static final class Spec {
        final String file;
        final int center;
        Spec(String file, int center) {
            this.file = file;
            this.center = center;
        }
    }

    private static final class Pending {
        final int soundId;
        final float rate;
        final float volume;
        final long dueAt;
        Pending(int soundId, float rate, float volume, long dueAt) {
            this.soundId = soundId;
            this.rate = rate;
            this.volume = volume;
            this.dueAt = dueAt;
        }
    }

    private final AssetManager assets;
    private final SoundPool pool;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Integer, Spec> specs = new HashMap<>();
    private final Map<String, Integer> soundIds = new HashMap<>();
    private final Set<Integer> loadedIds = new HashSet<>();
    private final Map<Integer, List<Pending>> pending = new HashMap<>();
    private final List<Integer> activeStreams = new ArrayList<>();
    private boolean released = false;

    GuitarSampler(Context context) {
        assets = context.getAssets();

        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();

        pool = new SoundPool.Builder()
            .setAudioAttributes(attrs)
            .setMaxStreams(12)
            .build();

        initSpecs();

        pool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
            List<Pending> ready;
            synchronized (GuitarSampler.this) {
                if (released) return;
                if (status == 0) loadedIds.add(sampleId);
                ready = pending.remove(sampleId);
            }
            if (status != 0 || ready == null) return;

            long now = SystemClock.uptimeMillis();
            for (Pending p : ready) {
                long lateness = now - p.dueAt;
                if (lateness > LATE_LIMIT_MS) continue;
                long delay = Math.max(0L, p.dueAt - now);
                scheduleLoaded(p.soundId, p.rate, p.volume, delay);
            }
        });

        preload();
    }

    synchronized void playMidiCsv(String csv) {
        if (released) return;

        final ArrayList<Integer> notes = new ArrayList<>();
        if (csv != null) {
            for (String raw : csv.split(",")) {
                try {
                    int midi = Integer.parseInt(raw.trim());
                    if (specs.containsKey(midi)) notes.add(midi);
                } catch (Exception ignored) {}
            }
        }

        handler.post(() -> playNotes(notes));
    }

    synchronized void stop() {
        if (released) return;
        handler.post(this::stopInternal);
    }

    synchronized void release() {
        if (released) return;
        released = true;
        handler.removeCallbacksAndMessages(null);
        for (Integer stream : activeStreams) {
            try { pool.stop(stream); } catch (Exception ignored) {}
        }
        activeStreams.clear();
        pending.clear();
        loadedIds.clear();
        soundIds.clear();
        pool.release();
    }

    private void playNotes(List<Integer> notes) {
        synchronized (this) {
            if (released) return;
        }

        stopInternal();

        long base = SystemClock.uptimeMillis() + 8L;
        for (int i = 0; i < notes.size(); i++) {
            int midi = notes.get(i);
            Spec spec = specs.get(midi);
            if (spec == null) continue;

            int soundId = ensureLoaded(spec.file);
            if (soundId == 0) continue;

            float rate = (float)Math.pow(2.0, (midi - spec.center) / 12.0);
            rate = Math.max(0.5f, Math.min(2.0f, rate));
            float volume = Math.max(0.42f, 0.78f - (i * 0.045f));
            long dueAt = base + (i * 24L);

            boolean loaded;
            synchronized (this) {
                loaded = loadedIds.contains(soundId);
                if (!loaded) {
                    pending.computeIfAbsent(soundId, k -> new ArrayList<>())
                        .add(new Pending(soundId, rate, volume, dueAt));
                }
            }

            if (loaded) {
                long delay = Math.max(0L, dueAt - SystemClock.uptimeMillis());
                scheduleLoaded(soundId, rate, volume, delay);
            }
        }
    }

    private void scheduleLoaded(int soundId, float rate, float volume, long delayMs) {
        handler.postDelayed(() -> {
            synchronized (GuitarSampler.this) {
                if (released) return;
            }
            int stream = pool.play(soundId, volume, volume, 1, 0, rate);
            if (stream != 0) {
                synchronized (GuitarSampler.this) {
                    activeStreams.add(stream);
                }
            }
        }, delayMs);
    }

    private void stopInternal() {
        handler.removeCallbacksAndMessages(null);

        synchronized (this) {
            for (Integer stream : activeStreams) {
                try { pool.stop(stream); } catch (Exception ignored) {}
            }
            activeStreams.clear();
            pending.clear();
        }
    }

    private void preload() {
        Set<String> unique = new HashSet<>();
        for (Spec spec : specs.values()) unique.add(spec.file);
        for (String file : unique) ensureLoaded(file);
    }

    private synchronized int ensureLoaded(String file) {
        if (released) return 0;

        Integer existing = soundIds.get(file);
        if (existing != null) return existing;

        try (AssetFileDescriptor afd = assets.openFd(BASE + file)) {
            int id = pool.load(
                afd.getFileDescriptor(),
                afd.getStartOffset(),
                afd.getLength(),
                1
            );
            if (id != 0) soundIds.put(file, id);
            return id;
        } catch (Exception e) {
            return 0;
        }
    }

    private void add(int lo, int hi, int center, String file) {
        for (int midi = lo; midi <= hi; midi++) {
            specs.put(midi, new Spec(file, center));
        }
    }

    private void initSpecs() {
        add(29,31,31,"G1.flac");
        add(32,32,32,"G#1.flac");
        add(33,33,33,"A1.flac");
        add(34,34,34,"A#1.flac");
        add(35,35,35,"B1.flac");
        add(36,36,36,"C2.flac");
        add(37,37,37,"C#2.flac");
        add(38,38,38,"D2.flac");
        add(39,39,39,"D#2.flac");
        add(40,40,40,"E2.flac");
        add(41,41,41,"F2.flac");
        add(42,43,43,"G2.flac");
        add(44,45,45,"A2.flac");
        add(46,47,47,"B2.flac");
        add(48,48,48,"C3.flac");
        add(49,50,50,"D3.flac");
        add(51,52,52,"E3.flac");
        add(53,53,53,"F3.flac");
        add(54,54,54,"F#3.flac");
        add(55,55,55,"G3.flac");
        add(56,56,56,"G#3.flac");
        add(57,57,57,"A3.flac");
        add(58,58,58,"A#3.flac");
        add(59,59,59,"B3.flac");
        add(60,60,60,"C4.flac");
        add(61,61,61,"C#4.flac");
        add(62,62,62,"D4.flac");
        add(63,63,63,"D#4.flac");
        add(64,64,64,"E4.flac");
        add(65,65,65,"F4.flac");
        add(66,66,66,"F#4.flac");
        add(67,67,67,"G4.flac");
        add(68,69,69,"A4.flac");
        add(70,70,70,"A#4.flac");
        add(71,71,71,"B4.flac");
        add(72,72,72,"C5.flac");
        add(73,73,73,"C#5.flac");
        add(74,74,74,"D5.flac");
        add(75,75,75,"D#5.flac");
        add(76,76,76,"E5.flac");
        add(77,77,77,"F5.flac");
        add(78,78,78,"F#5.flac");
        add(79,79,79,"G5.flac");
        add(80,80,80,"G#5.flac");
        add(81,81,81,"A5.flac");
        add(82,82,82,"A#5.flac");
        add(83,83,83,"B5.flac");
        add(84,88,84,"C6.flac");
    }
}
