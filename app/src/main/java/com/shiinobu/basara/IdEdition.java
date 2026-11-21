package com.shiinobu.basara;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * The Indonesian edition: which translation the game is (none, a release, a patch that stopped
 * halfway), and the translation project's Wii patches on GitHub (releases tagged "wii-v<version>":
 * the full patch basara2_wii_id_v<version>.patch, and small updaters
 * basara2_wii_id_v<from>_to_v<version>.patch). IdPatch puts a patch into the unpacked game.
 */
final class IdEdition {
    private IdEdition() {}

    static final String PROJECT_URL = "https://github.com/" + BuildConfig.PATCH_REPO;
    static final String SITE_URL = "https://dezer04.github.io/sengoku-basara-2-heroes-id/wii.html";
    private static final String RELEASES =
            "https://api.github.com/repos/" + BuildConfig.PATCH_REPO + "/releases?per_page=30";

    /** linkData.afs, linkDt01.afs, linkDt02.afs: the original disc's (AppFiles) and the translation's. */
    private static final long[] ID_ARCHIVES = {706342448L, 1468640336L, 1879459840L};
    /** ISO file sizes of the translation's releases, for Read mode (an RVZ has its own size). */
    private static final Object[][] ISO_SIZES = {{4194336768L, "0.99.1"}, {4454055936L, "0.99"}};
    /**
     * linkData.afs entry 3137 (the title screen with the version) tells the releases apart:
     * size and CRC-32 per version. The original's is the Japanese title.
     */
    private static final int TITLE_ENTRY = 3137;
    private static final Object[][] TITLE_PRINTS = {
            {67326L, 0xff19a1ccL, "original"},
            {79617L, 0xd15026d4L, "0.99"},
            {79442L, 0x703b1cfdL, "0.99.1"},
    };

    /** What the game is. */
    static final class State {
        /** The original Japanese disc's archives. */
        boolean original;
        /** The Indonesian translation (version may still be unknown). */
        boolean indonesian;
        /** "0.99.1", or null when unknown. */
        String version;
        /** A patch stopped halfway: the game must be patched again before it plays. */
        boolean incomplete;
        /** Read mode: played from the ISO or RVZ, so a patch first needs the game unpacked. */
        boolean readMode;

        /** "Bahasa Indonesia v0.99.1", "Japanese (original)", ... */
        String describe() {
            if (incomplete) return Tr.t("Indonesian patch not finished");
            if (indonesian) return version != null ? Tr.t("Indonesian v") + version : Tr.t("Indonesian");
            if (original) return Tr.t("Japanese (original)");
            return Tr.t("changed");
        }
    }

    private static State cached;
    private static String cachedKey;

    /** Forget what was found (after an import or a patch). */
    static void invalidate() {
        cached = null;
        cachedKey = null;
    }

    /** What the game is now. Reads a few small things; cached until the game files change. */
    static State state(Context context) {
        final File game = AppFiles.gameDir(context);
        final String key = AppFiles.discSignature(context) + "|" + AppFiles.gameSource(context) + "|"
                + new File(game, IdPatch.MARKER).lastModified();
        if (cached != null && key.equals(cachedKey)) return cached;
        final State state = new State();
        state.readMode = AppFiles.gameSource(context) != null;
        final long[] sizes = AppFiles.currentArchiveSizes(context);
        state.original = AppFiles.matchesOriginal(sizes);
        state.indonesian = java.util.Arrays.equals(sizes, ID_ARCHIVES);
        if (!state.readMode) {
            final JSONObject marker = IdPatch.readMarker(game);
            if (marker != null && "patching".equals(marker.optString("state"))) {
                state.incomplete = true;
                state.indonesian = false;
                state.original = false;
            } else if (marker != null && "ok".equals(marker.optString("state"))) {
                state.indonesian = true;
                state.original = false;
                state.version = marker.optString("version", null);
            } else if (state.indonesian || state.original) {
                final String print = titlePrint(new File(game, "files/linkData.afs"));
                if ("original".equals(print)) {
                    state.indonesian = false;
                } else if (print != null) {
                    state.indonesian = true;
                    state.original = false;
                    state.version = print;
                }
            }
        } else if (state.indonesian) {
            state.version = isoVersion(context, AppFiles.gameSource(context));
        }
        cached = state;
        cachedKey = key;
        return state;
    }

    /** The version entry 3137 of linkData.afs names, "original", or null. */
    private static String titlePrint(File afs) {
        try (RandomAccessFile f = new RandomAccessFile(afs, "r")) {
            f.seek(8 + 8L * TITLE_ENTRY);
            final byte[] entry = new byte[8];
            f.readFully(entry);
            final long offset = le32(entry, 0), size = le32(entry, 4);
            for (Object[] print : TITLE_PRINTS) {
                if ((Long) print[0] != size) continue;
                final byte[] data = new byte[(int) size];
                f.seek(offset);
                f.readFully(data);
                final CRC32 crc = new CRC32();
                crc.update(data);
                if (crc.getValue() == (Long) print[1]) return (String) print[2];
            }
        } catch (IOException | RuntimeException ignored) {
            // Not this game's archive, or a version this table doesn't know.
        }
        return null;
    }

    /** Read mode: the release an ISO's size names, or null (an RVZ, or unknown). */
    private static String isoVersion(Context context, Uri uri) {
        try (ParcelFileDescriptor file = context.getContentResolver().openFileDescriptor(uri, "r")) {
            final long size = file != null ? file.getStatSize() : -1;
            for (Object[] iso : ISO_SIZES) {
                if ((Long) iso[0] == size) return (String) iso[1];
            }
        } catch (Exception ignored) {
            // The file went away; AppFiles.gameProblem says so.
        }
        return null;
    }

    private static long le32(byte[] b, int at) {
        return (b[at] & 0xFFL) | (b[at + 1] & 0xFFL) << 8 | (b[at + 2] & 0xFFL) << 16 | (b[at + 3] & 0xFFL) << 24;
    }

    // --- the translation's releases on GitHub -----------------------------------------------------

    /** One downloadable patch. */
    static final class PatchFile {
        String name;
        String url;
        long size;
    }

    /** The newest Wii release of the translation. */
    static final class Release {
        String version;
        String tag;
        PatchFile full;
        /** Updaters by the version they update from. */
        final Map<String, PatchFile> updaters = new LinkedHashMap<>();

        /** The patch for a game at `installed` (null: the original or an unknown version): an updater when one fits. */
        PatchFile patchFor(String installed) {
            if (installed != null && updaters.containsKey(installed)) return updaters.get(installed);
            return full;
        }
    }

    /** The newest Wii release with a full patch, or null when there is none. Network: off the UI thread. */
    static Release latest() throws IOException {
        final HttpURLConnection connection = Updater.open(Updater.debugProperty("debug.basara.patch_releases_url", RELEASES));
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            final int code;
            try {
                code = connection.getResponseCode();
            } catch (IOException e) {
                throw new IOException(Tr.t("couldn't reach GitHub. Check the internet connection and try again."));
            }
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null;
            if (code != HttpURLConnection.HTTP_OK) throw new IOException(Tr.t("GitHub answered ") + code);
            final JSONArray releases = new JSONArray(Updater.readAll(connection.getInputStream()));
            Release best = null;
            final Pattern tagPattern = Pattern.compile("^wii-v([0-9][0-9.]*)$");
            for (int r = 0; r < releases.length(); ++r) {
                final JSONObject json = releases.getJSONObject(r);
                if (json.optBoolean("draft", false)) continue;
                final Matcher tag = tagPattern.matcher(json.optString("tag_name", ""));
                if (!tag.matches()) continue;
                final String version = tag.group(1);
                if (best != null && !Updater.isNewer(version, best.version)) continue;
                final Release release = new Release();
                release.version = version;
                release.tag = json.getString("tag_name");
                final Pattern updater = Pattern.compile("^basara2_wii_id_v([0-9.]+)_to_v" + Pattern.quote(version) + "\\.patch$");
                final JSONArray assets = json.getJSONArray("assets");
                for (int i = 0; i < assets.length(); ++i) {
                    final JSONObject asset = assets.getJSONObject(i);
                    final PatchFile file = new PatchFile();
                    file.name = asset.getString("name");
                    file.url = asset.getString("browser_download_url");
                    file.size = asset.optLong("size", -1);
                    final Matcher from = updater.matcher(file.name);
                    if (file.name.equals("basara2_wii_id_v" + version + ".patch")) {
                        release.full = file;
                    } else if (from.matches()) {
                        release.updaters.put(from.group(1), file);
                    }
                }
                if (release.full != null) best = release;
            }
            return best;
        } catch (JSONException e) {
            throw new IOException(Tr.t("unreadable answer from GitHub: ") + e.getMessage());
        } finally {
            connection.disconnect();
        }
    }

    /**
     * A download read as it arrives, which picks up where it stopped when the connection drops (an
     * HTTP range request), so a phone that loses its signal for a moment doesn't start over.
     */
    static final class Download extends InputStream {
        private static final int RETRIES = 8;
        private final String url;
        private final long size;
        private long position;
        private HttpURLConnection connection;
        private InputStream in;
        private volatile boolean closed;

        Download(String url, long size) throws IOException {
            this.url = url;
            this.size = size;
            connect();
        }

        /** Bytes received so far. */
        long position() { return position; }

        private void connect() throws IOException {
            disconnect();
            final HttpURLConnection c = Updater.open(url);
            c.setReadTimeout(30000);
            if (position > 0) c.setRequestProperty("Range", "bytes=" + position + "-");
            final int code = c.getResponseCode();
            if (position > 0 ? code != HttpURLConnection.HTTP_PARTIAL : code != HttpURLConnection.HTTP_OK) {
                c.disconnect();
                throw new IOException(Tr.t("The download stopped (the server answered ") + code + ").");
            }
            connection = c;
            in = c.getInputStream();
        }

        private void disconnect() {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // going anyway
                }
            }
            if (connection != null) connection.disconnect();
            in = null;
            connection = null;
        }

        @Override
        public int read() throws IOException {
            final byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            for (int attempt = 0; ; ++attempt) {
                try {
                    if (in == null) connect();
                    final int n = in.read(b, off, len);
                    if (n < 0 && size > 0 && position < size) throw new IOException("connection closed early");
                    if (n > 0) position += n;
                    return n;
                } catch (IOException e) {
                    if (closed || attempt >= RETRIES) {
                        throw new IOException(Tr.t("The download stopped. Check the internet connection and try again."), e);
                    }
                    disconnect();  // connected again on the next turn of the loop
                    try {
                        Thread.sleep(Math.min(16000, 1000L << attempt));
                    } catch (InterruptedException stop) {
                        throw new IOException(stop);
                    }
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            disconnect();
        }
    }
}
