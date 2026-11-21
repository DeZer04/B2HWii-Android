package com.shiinobu.basara;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The Indonesian translation's Wii patch (BSR2WII2, made by make_wii_patch.py in the translation
 * project), put straight into the unpacked game (sys/ and files/) instead of into a new ISO: no
 * encryption, no hashes, no 4 GB image to write. The result is the same tree an ISO made by
 * patch_wii.py unpacks to: main.dol with the patched ranges, linkData.afs and linkDt01.afs rebuilt with
 * the same rule (entries back to back, 0x800 aligned, then the name table), the whole files replaced,
 * and sys/fst.bin with the new offsets and sizes (the runtime reads files by their FST disc offset, so a
 * grown archive with the old FST would run into the next file).
 *
 * The patch is read once, front to back: a file, or a download read as it arrives. Each archive is
 * written next to the old one and then takes its place, so at most one archive's size is needed free.
 * No Android classes here, so the same code is tested on a PC against the patch_wii.py result.
 */
final class IdPatch {
    private IdPatch() {}

    static final String MAGIC = "BSR2WII2";
    /** The game folder's note of the installed translation (IdEdition reads it). */
    static final String MARKER = ".basara2-id.json";
    private static final int AFS_ALIGN = 0x800;
    private static final int FST_ALIGN = 0x8000;
    private static final String TMP = ".idpatch";

    /** A failure for the player: an English text for Tr, with String.format arguments. */
    static final class Failure extends Exception {
        final Object[] args;

        Failure(String text, Object... args) {
            super(text);
            this.args = args;
        }
    }

    /** What extract-style callers get back when the player stopped it. */
    static final String CANCELLED = "";

    /** Bytes written so far and in all; false stops the patch. */
    interface Progress {
        boolean update(long done, long total);
    }

    /** The patch's header: version, game, and for an updater the source it needs. */
    static final class Info {
        String game;
        String version;
        /** An updater's source version ("0.99"), or null for a full patch. */
        String from;
        /** An updater's source archive sizes: the patch fits only a game with exactly these. */
        final Map<String, Long> src = new LinkedHashMap<>();
        JSONObject manifest;

        boolean updater() { return !src.isEmpty(); }
    }

    // --- reading the header -------------------------------------------------------------------------

    /** Reads the header and leaves `in` at the payload stream. */
    static Info readInfo(InputStream in) throws IOException, Failure {
        final byte[] magic = new byte[8];
        readFully(in, magic, 0, magic.length);
        if (!MAGIC.equals(new String(magic, StandardCharsets.US_ASCII))) {
            throw new Failure("That file isn't a patch of the Indonesian translation for Wii (.patch, BSR2WII2).");
        }
        final byte[] json = new byte[le32(in, 1 << 24)];
        readFully(in, json, 0, json.length);
        skip(in, le32(in, 1 << 20));  // the new TMD, for an ISO
        skip(in, le32(in, 1 << 20));  // the H3 table, for an ISO
        final Info info = new Info();
        try {
            info.manifest = new JSONObject(new String(json, StandardCharsets.UTF_8));
            info.game = info.manifest.getString("game");
            info.version = info.manifest.getString("version");
            final JSONObject src = info.manifest.optJSONObject("src");
            if (src != null) {
                for (Iterator<String> it = src.keys(); it.hasNext(); ) {
                    final String name = it.next();
                    info.src.put(name, src.getLong(name));
                }
                info.from = info.manifest.optString("from", null);
            }
        } catch (JSONException e) {
            throw new Failure("That patch file is damaged. Download it again.");
        }
        if (info.manifest.optInt("format", 0) != 2) {
            throw new Failure("This patch needs a newer version of the app.");
        }
        return info;
    }

    // --- applying -----------------------------------------------------------------------------------

    /** One file of the game, as sys/fst.bin lists it. */
    private static final class FstFile {
        final int index;
        final String path;  // under files/
        final long offset;

        FstFile(int index, String path, long offset) {
            this.index = index;
            this.path = path;
            this.offset = offset;
        }
    }

    /** An archive to rebuild: its old table and the entries the patch brings. */
    private static final class AfsPlan {
        String name;
        File source;
        byte[] head;               // the first 8 bytes ("AFS\0", count)
        long[] offsets, sizes;     // the old table
        long nameTableOffset, nameTableSize;
        final Map<Integer, Long> replaced = new LinkedHashMap<>();  // entry -> new size, in stream order
        long[] newOffsets, newSizes;
        long end;                  // where the name table goes

        long total() { return end + nameTableSize; }
    }

    /**
     * Puts the patch read from `in` (positioned at its start) into the unpacked game at `game`, and
     * notes the version in MARKER. Returns the patch's header, or null when `progress` stopped it.
     * Throws Failure with a reason for the player. Until the first file is replaced nothing changes; from
     * then on MARKER says "patching" until the end, so a patch that stopped is seen and done again.
     */
    static Info apply(File game, InputStream in, Progress progress) throws IOException, Failure {
        final Info info = readInfo(in);
        if (!"RBSJ08".equals(info.game)) {
            throw new Failure("This patch is for %s, not Sengoku Basara 2 Heroes (RBSJ08).", info.game);
        }
        final File sys = new File(game, "sys");
        final File files = new File(game, "files");
        final byte[] boot = readFile(new File(sys, "boot.bin"));
        final byte[] fst = readFile(new File(sys, "fst.bin"));
        if (boot.length < 0x440 || fst.length < 12) throw new Failure("The unpacked game is incomplete. Choose your ISO or RVZ again.");
        final List<FstFile> fstFiles = fstFiles(fst);

        // An updater fits only the release it updates: its archives have exactly these sizes.
        for (Map.Entry<String, Long> need : info.src.entrySet()) {
            final File file = new File(files, need.getKey());
            if (!file.isFile() || file.length() != need.getValue()) {
                throw new Failure("This patch is an updater for the Indonesian v%s only, and this game isn't that "
                        + "version (%s differs). Use the full patch.", info.from != null ? info.from : "?", need.getKey());
            }
        }

        final JSONObject man = info.manifest;
        final List<String[]> stream = new ArrayList<>();
        final List<AfsPlan> plans = new ArrayList<>();
        final Map<String, Long> whole = new LinkedHashMap<>();
        long dolBytes = 0;
        final List<long[]> dolRanges = new ArrayList<>();
        try {
            final JSONArray order = man.getJSONArray("stream");
            for (int i = 0; i < order.length(); ++i) {
                final JSONArray item = order.getJSONArray(i);
                stream.add(new String[] {item.getString(0), item.getString(1)});
            }
            final JSONArray dol = man.optJSONArray("dol");
            for (int i = 0; dol != null && i < dol.length(); ++i) {
                final long at = dol.getJSONArray(i).getLong(0), size = dol.getJSONArray(i).getLong(1);
                dolRanges.add(new long[] {at, size});
                dolBytes += size;
            }
            final JSONObject afs = man.optJSONObject("afs");
            final JSONObject wholes = man.optJSONObject("whole");
            for (String[] item : stream) {
                if (item[0].equals("afs")) {
                    plans.add(planAfs(files, item[1], afs.getJSONArray(item[1])));
                } else if (item[0].equals("whole")) {
                    whole.put(item[1], wholes.getLong(item[1]));
                } else if (!item[0].equals("dol")) {
                    throw new Failure("This patch needs a newer version of the app.");
                }
            }
        } catch (JSONException e) {
            throw new Failure("That patch file is damaged. Download it again.");
        }

        // Room for the largest new file next to its old copy.
        long total = dolBytes, largest = 0;
        for (AfsPlan plan : plans) {
            total += plan.total();
            largest = Math.max(largest, plan.total());
        }
        for (long size : whole.values()) {
            total += size;
            largest = Math.max(largest, size);
        }
        final long free = files.getUsableSpace();
        if (largest + (64L << 20) > free) {
            throw new Failure("The patch needs %.1f GB of free space while it works, and this phone has %.1f GB.",
                    (largest + (64L << 20)) / (double) (1L << 30), free / (double) (1L << 30));
        }

        final Payload payload = new Payload(in);
        final long[] done = {0, 0};  // bytes written, and written when progress was last told
        final long totalBytes = total;
        final Progress counted = progress == null ? (d, t) -> true : progress;
        int planIndex = 0;
        byte[][] dolData = null;
        final JSONObject patching = new JSONObject();
        try {
            patching.put("state", "patching").put("version", info.version);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        boolean touched = false;
        try {
            for (String[] item : stream) {
                switch (item[0]) {
                    case "dol":
                        // Kept until the archives are in: a stopped patch leaves main.dol as it was.
                        dolData = new byte[dolRanges.size()][];
                        for (int i = 0; i < dolRanges.size(); ++i) {
                            dolData[i] = new byte[(int) dolRanges.get(i)[1]];
                            payload.readFully(dolData[i], 0, dolData[i].length);
                        }
                        break;
                    case "afs": {
                        final AfsPlan plan = plans.get(planIndex++);
                        final File tmp = new File(files, plan.name + TMP);
                        if (!writeAfs(plan, tmp, payload, done, totalBytes, counted)) {
                            delete(tmp);
                            return null;
                        }
                        if (!touched) writeMarker(game, patching);
                        touched = true;
                        replace(tmp, plan.source);
                        break;
                    }
                    case "whole": {
                        final File target = new File(files, item[1]);
                        final File tmp = new File(files, item[1] + TMP);
                        try (FileOutputStream out = new FileOutputStream(tmp)) {
                            if (!payload.copyTo(out.getChannel(), whole.get(item[1]), done, totalBytes, counted)) {
                                out.close();
                                delete(tmp);
                                return null;
                            }
                            out.getFD().sync();
                        }
                        if (!touched) writeMarker(game, patching);
                        touched = true;
                        replace(tmp, target);
                        break;
                    }
                    default:
                        break;
                }
            }
        } catch (EOFException e) {
            throw new Failure("That patch file is cut short (the download didn't finish). Download it again.");
        } catch (DataFormatException e) {
            throw new Failure("That patch file is damaged. Download it again.");
        }

        if (!touched) writeMarker(game, patching);
        if (dolData != null) {
            try (RandomAccessFile dol = new RandomAccessFile(new File(sys, "main.dol"), "rw")) {
                for (int i = 0; i < dolRanges.size(); ++i) {
                    dol.seek(dolRanges.get(i)[0]);
                    dol.write(dolData[i]);
                    done[0] += dolData[i].length;
                }
                dol.getFD().sync();
            }
        }
        writeFst(sys, files, boot, fst, fstFiles);
        try {
            final JSONObject ok = new JSONObject().put("state", "ok").put("version", info.version)
                    .put("time", System.currentTimeMillis());
            if (info.from != null) ok.put("from", info.from);
            writeMarker(game, ok);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        counted.update(totalBytes, totalBytes);
        return info;
    }

    /** The archive's old table, the patch's entries for it, and the new layout. */
    private static AfsPlan planAfs(File files, String name, JSONArray entries) throws IOException, JSONException, Failure {
        final AfsPlan plan = new AfsPlan();
        plan.name = name;
        plan.source = new File(files, name);
        if (!plan.source.isFile()) throw new Failure("The game has no %s. Choose your ISO or RVZ again.", name);
        try (RandomAccessFile f = new RandomAccessFile(plan.source, "r")) {
            plan.head = new byte[8];
            f.readFully(plan.head);
            final int count = ByteBuffer.wrap(plan.head, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (plan.head[0] != 'A' || plan.head[1] != 'F' || plan.head[2] != 'S' || count <= 0 || count > (1 << 20)) {
                throw new Failure("The game's %s is damaged. Choose your ISO or RVZ again.", name);
            }
            final byte[] table = new byte[8 * count + 8];
            f.readFully(table);
            final ByteBuffer t = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
            plan.offsets = new long[count];
            plan.sizes = new long[count];
            for (int i = 0; i < count; ++i) {
                plan.offsets[i] = t.getInt() & 0xFFFFFFFFL;
                plan.sizes[i] = t.getInt() & 0xFFFFFFFFL;
            }
            plan.nameTableOffset = t.getInt() & 0xFFFFFFFFL;
            plan.nameTableSize = t.getInt() & 0xFFFFFFFFL;
            final long length = f.length();
            for (int i = 0; i < count; ++i) {
                if (plan.offsets[i] + plan.sizes[i] > length) throw new Failure("The game's %s is damaged. Choose your ISO or RVZ again.", name);
            }
            if (plan.nameTableOffset + plan.nameTableSize > length) throw new Failure("The game's %s is damaged. Choose your ISO or RVZ again.", name);
        }
        int last = -1;
        for (int i = 0; i < entries.length(); ++i) {
            final int index = entries.getJSONArray(i).getInt(0);
            // The payloads come in entry order; the archive is written in entry order too.
            if (index <= last || index >= plan.offsets.length) throw new Failure("That patch file is damaged. Download it again.");
            last = index;
            plan.replaced.put(index, entries.getJSONArray(i).getLong(1));
        }
        final int count = plan.offsets.length;
        plan.newOffsets = new long[count];
        plan.newSizes = new long[count];
        long pos = plan.offsets[0];
        for (int i = 0; i < count; ++i) {
            final Long size = plan.replaced.get(i);
            final long s = size != null ? size : plan.sizes[i];
            plan.newOffsets[i] = pos;
            plan.newSizes[i] = s;
            pos += s + (AFS_ALIGN - s % AFS_ALIGN) % AFS_ALIGN;
        }
        plan.end = pos;
        if (8 + 8L * count + 8 > plan.offsets[0] || plan.total() > 0xFFFFFFFFL) {
            throw new Failure("The game's %s is damaged. Choose your ISO or RVZ again.", name);
        }
        return plan;
    }

    private static boolean writeAfs(AfsPlan plan, File tmp, Payload payload, long[] done, long total, Progress progress)
            throws IOException, DataFormatException {
        final int count = plan.offsets.length;
        try (FileInputStream sourceStream = new FileInputStream(plan.source);
             FileOutputStream outStream = new FileOutputStream(tmp)) {
            final FileChannel source = sourceStream.getChannel();
            final FileChannel out = outStream.getChannel();
            final byte[] header = new byte[(int) plan.offsets[0]];  // past the table it stays zero
            final ByteBuffer table = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            table.put(plan.head);
            for (int i = 0; i < count; ++i) {
                table.putInt((int) plan.newOffsets[i]).putInt((int) plan.newSizes[i]);
            }
            table.putInt((int) plan.end).putInt((int) plan.nameTableSize);
            writeAll(out, ByteBuffer.wrap(header));
            long pos = plan.offsets[0];
            done[0] += pos;
            for (int i = 0; i < count; ++i) {
                pos = zeros(out, pos, plan.newOffsets[i], done);
                final long size = plan.newSizes[i];
                if (plan.replaced.containsKey(i)) {
                    if (!payload.copyTo(out, size, done, total, progress)) return false;
                } else {
                    if (!copyRange(source, plan.offsets[i], size, out, done, total, progress)) return false;
                }
                pos += size;
            }
            pos = zeros(out, pos, plan.end, done);
            if (!copyRange(source, plan.nameTableOffset, plan.nameTableSize, out, done, total, progress)) return false;
            pos += plan.nameTableSize;
            if (out.position() != plan.total() || pos != plan.total()) throw new IOException("archive size mismatch");
            outStream.getFD().sync();
        }
        return true;
    }

    // --- sys/fst.bin -------------------------------------------------------------------------------

    /** Every file of the FST with its path under files/ and its disc offset. */
    private static List<FstFile> fstFiles(byte[] fst) throws Failure {
        final int count = be32(fst, 8);
        if (count <= 0 || 12L * count > fst.length) throw new Failure("The unpacked game is incomplete. Choose your ISO or RVZ again.");
        final int names = 12 * count;
        final List<FstFile> out = new ArrayList<>();
        // Folders as (path, end index), like disc_extract's ReadFileList.
        final List<String> folderPaths = new ArrayList<>();
        final List<Integer> folderEnds = new ArrayList<>();
        folderPaths.add("");
        folderEnds.add(count);
        for (int i = 1; i < count; ++i) {
            while (i >= folderEnds.get(folderEnds.size() - 1)) {
                if (folderEnds.size() == 1) throw new Failure("The unpacked game is incomplete. Choose your ISO or RVZ again.");
                folderEnds.remove(folderEnds.size() - 1);
                folderPaths.remove(folderPaths.size() - 1);
            }
            final int e = 12 * i;
            final int nameAt = names + (be32(fst, e) & 0xFFFFFF);
            int nameEnd = nameAt;
            while (nameEnd < fst.length && fst[nameEnd] != 0) ++nameEnd;
            if (nameAt >= fst.length) throw new Failure("The unpacked game is incomplete. Choose your ISO or RVZ again.");
            final String name = new String(fst, nameAt, nameEnd - nameAt, StandardCharsets.ISO_8859_1);
            final String parent = folderPaths.get(folderPaths.size() - 1);
            if (fst[e] != 0) {
                folderPaths.add(parent + name + "/");
                folderEnds.add(be32(fst, e + 8));
            } else {
                out.add(new FstFile(i, parent + name, (be32(fst, e + 4) & 0xFFFFFFFFL) << 2));
            }
        }
        return out;
    }

    /**
     * Lays the files out as patch_wii.py does on the disc: in their old order, 32 KiB aligned, from the
     * first file's offset (or the end of the FST), each with its size on disk.
     */
    private static void writeFst(File sys, File files, byte[] boot, byte[] fst, List<FstFile> fstFiles) throws IOException {
        final long fstOffset = (be32(boot, 0x424) & 0xFFFFFFFFL) << 2;
        final long fstSize = (be32(boot, 0x428) & 0xFFFFFFFFL) << 2;
        final List<FstFile> order = new ArrayList<>(fstFiles);
        order.sort((a, b) -> Long.compare(a.offset, b.offset));  // stable: ties keep FST order
        long pos = Long.MAX_VALUE;
        for (FstFile file : fstFiles) pos = Math.min(pos, file.offset);
        pos = Math.max(pos, fstOffset + fstSize);
        final byte[] out = fst.clone();
        for (FstFile file : order) {
            final File host = new File(files, file.path);
            if (!host.isFile()) throw new IOException("missing " + host);
            final long size = host.length();
            pos = (pos + FST_ALIGN - 1) / FST_ALIGN * FST_ALIGN;
            putBe32(out, 12 * file.index + 4, (int) (pos >> 2));
            putBe32(out, 12 * file.index + 8, (int) size);
            pos += size;
        }
        final File tmp = new File(sys, "fst.bin" + TMP);
        try (FileOutputStream stream = new FileOutputStream(tmp)) {
            stream.write(out);
            stream.getFD().sync();
        }
        replace(tmp, new File(sys, "fst.bin"));
    }

    // --- the payload stream -------------------------------------------------------------------------

    /** The patch's one zlib stream of payloads, inflated as it is used. */
    private static final class Payload {
        private final InputStream in;
        private final Inflater inflater = new Inflater();
        private final byte[] input = new byte[1 << 16];
        private final byte[] buffer = new byte[1 << 20];

        Payload(InputStream in) { this.in = in; }

        /** Up to `length` inflated bytes into `out`; at least one. */
        private int inflate(byte[] out, int offset, int length) throws IOException, DataFormatException {
            while (true) {
                final int got = inflater.inflate(out, offset, length);
                if (got > 0) return got;
                if (inflater.finished() || inflater.needsDictionary()) throw new EOFException();
                if (inflater.needsInput()) {
                    final int read = in.read(input);
                    if (read < 0) throw new EOFException();
                    inflater.setInput(input, 0, read);
                }
            }
        }

        void readFully(byte[] out, int offset, int length) throws IOException, DataFormatException {
            while (length > 0) {
                final int got = inflate(out, offset, length);
                offset += got;
                length -= got;
            }
        }

        boolean copyTo(FileChannel out, long size, long[] done, long total, Progress progress)
                throws IOException, DataFormatException {
            while (size > 0) {
                final int got = inflate(buffer, 0, (int) Math.min(size, buffer.length));
                writeAll(out, ByteBuffer.wrap(buffer, 0, got));
                size -= got;
                done[0] += got;
                if (!report(done, total, progress)) return false;
            }
            return true;
        }
    }

    // --- files --------------------------------------------------------------------------------------

    /** Copies `size` bytes at `offset` of `source` to the end of `out` (in the kernel where it can). */
    private static boolean copyRange(FileChannel source, long offset, long size, FileChannel out, long[] done, long total,
                                     Progress progress) throws IOException {
        while (size > 0) {
            final long moved = source.transferTo(offset, Math.min(size, 16L << 20), out);
            if (moved <= 0) throw new IOException("short read");
            offset += moved;
            size -= moved;
            done[0] += moved;
            if (!report(done, total, progress)) return false;
        }
        return true;
    }

    /** Tells `progress` every 8 MiB written, across entries (an archive of small entries too). */
    private static boolean report(long[] done, long total, Progress progress) {
        if (done[0] - done[1] < (8 << 20)) return true;
        done[1] = done[0];
        return progress.update(done[0], total);
    }

    private static final ByteBuffer ZEROS = ByteBuffer.allocate(AFS_ALIGN);

    /** Zero bytes from `pos` up to `to`; returns `to`. */
    private static long zeros(FileChannel out, long pos, long to, long[] done) throws IOException {
        while (pos < to) {
            final ByteBuffer zero = ZEROS.duplicate();
            zero.limit((int) Math.min(zero.capacity(), to - pos));
            final int n = zero.remaining();
            writeAll(out, zero);
            pos += n;
            done[0] += n;
        }
        return pos;
    }

    private static void writeAll(FileChannel out, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) out.write(buffer);
    }

    /** `tmp` takes `target`'s name. */
    private static void replace(File tmp, File target) throws IOException {
        if (tmp.renameTo(target)) return;
        delete(target);
        if (!tmp.renameTo(target)) throw new IOException("cannot replace " + target);
    }

    private static void delete(File file) {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    // --- the game folder's note of the translation ----------------------------------------------

    /** The note in `game` ({"state": "ok"|"patching", "version": ...}), or null when there is none. */
    static JSONObject readMarker(File game) {
        try {
            return new JSONObject(new String(readFile(new File(game, MARKER)), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** Writes the note; `marker` null removes it. */
    static void writeMarker(File game, JSONObject marker) throws IOException {
        final File file = new File(game, MARKER);
        if (marker == null) {
            delete(file);
            return;
        }
        final File tmp = new File(game, MARKER + TMP);
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(marker.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        replace(tmp, file);
    }

    /** Leftovers of a patch that stopped (the old archives are still in place). */
    static void cleanUp(File game) {
        for (String dir : new String[] {"files", "sys"}) {
            final File[] list = new File(game, dir).listFiles();
            if (list == null) continue;
            for (File file : list) {
                if (file.getName().endsWith(TMP)) delete(file);
            }
        }
    }

    static byte[] readFile(File file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            if (f.length() > (64 << 20)) throw new IOException("too large: " + file);
            final byte[] bytes = new byte[(int) f.length()];
            f.readFully(bytes);
            return bytes;
        }
    }

    static void readFully(InputStream in, byte[] out, int offset, int length) throws IOException {
        while (length > 0) {
            final int read = in.read(out, offset, length);
            if (read < 0) throw new EOFException();
            offset += read;
            length -= read;
        }
    }

    private static void skip(InputStream in, long count) throws IOException {
        final byte[] scratch = new byte[8192];
        while (count > 0) {
            final int read = in.read(scratch, 0, (int) Math.min(count, scratch.length));
            if (read < 0) throw new EOFException();
            count -= read;
        }
    }

    /** A little-endian u32 length from `in`, at most `max`. */
    private static int le32(InputStream in, int max) throws IOException, Failure {
        final byte[] b = new byte[4];
        readFully(in, b, 0, 4);
        final long value = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
        if (value > max) throw new Failure("That patch file is damaged. Download it again.");
        return (int) value;
    }

    static int be32(byte[] b, int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | (b[at + 3] & 0xFF);
    }

    private static void putBe32(byte[] b, int at, int value) {
        b[at] = (byte) (value >>> 24);
        b[at + 1] = (byte) (value >>> 16);
        b[at + 2] = (byte) (value >>> 8);
        b[at + 3] = (byte) value;
    }
}
