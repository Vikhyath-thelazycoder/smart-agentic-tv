package io.github.smartagentictv.tv;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Videos, music and photos on USB drives and the TV, with a real preview of each. Nothing slow runs on the main thread:
 * folders are read and pictures decoded in the background, and thumbnails are kept on disk, so a press answers at once.
 */
public class MediaActivity extends Activity {
    private static final List<String> VIDEO = Arrays.asList("mp4", "mkv", "avi", "mov", "m4v", "webm", "ts", "mpg", "mpeg", "wmv", "flv", "3gp");
    private static final List<String> MUSIC = Arrays.asList("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma");
    private static final List<String> PHOTO = Arrays.asList("jpg", "jpeg", "png", "webp", "gif", "bmp");
    private static final List<String> SKIP = Arrays.asList("android", "lost.dir", "system volume information", "$recycle.bin");
    private static final int THUMB_W = 480, FULL_W = 1280, FULL_H = 720;

    private static final class Entry {
        final File file;
        final String title, kind;   // kind: Drive, Folder, Video, Music, Photo
        Entry(File file, String title, String kind) {
            this.file = file;
            this.title = title;
            this.kind = kind;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService lister = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbs = Executors.newFixedThreadPool(2);
    private final ExecutorService fulls = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(32 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };
    private final ArrayDeque<File> path = new ArrayDeque<>();
    private final Map<String, Integer> remembered = new HashMap<>();
    private final Runnable applyPreview = this::showPreview;
    private List<Entry> entries = new ArrayList<>();
    private Entry focused;
    private GridView grid;
    private TilesAdapter adapter;
    private ImageView preview;
    private TextView heading, name, detail, empty;
    private int generation;
    private String titleOfRoot = "USB drive";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        // VLC and the TV's player take a plain file path; this is our own TV, so no content provider is needed.
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());
        FrameLayout root = new FrameLayout(this);
        ImageView wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wall.setImageBitmap(Daily.wallpaper(this));
        root.addView(wall, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout page = new LinearLayout(this);
        page.setPadding(44, 36, 44, 36);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(0, 0, 30, 0);
        heading = text("Media", 42, Color.WHITE, Ui.light);
        heading.setSingleLine(true);
        left.addView(heading);
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setClipToOutline(true);
        GradientDrawable box = new GradientDrawable();
        box.setCornerRadius(22);
        box.setColor(0x33000000);
        box.setStroke(1, 0x4DFFFFFF);
        preview.setBackground(box);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, 220);
        pl.setMargins(0, 22, 0, 18);
        left.addView(preview, pl);
        name = text("", 24, Color.WHITE, Ui.medium);
        name.setMaxLines(3);
        left.addView(name);
        detail = text("", 18, 0xB3FFFFFF, Ui.regular);
        detail.setPadding(0, 8, 0, 0);
        left.addView(detail);
        page.addView(left, new LinearLayout.LayoutParams(400, -1));

        FrameLayout panel = new FrameLayout(this);
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x47FFFFFF, 0x24FFFFFF});
        glass.setCornerRadius(30);
        glass.setStroke(1, 0x4DFFFFFF);
        panel.setBackground(glass);
        panel.setPadding(16, 16, 16, 16);
        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setVerticalSpacing(10);
        grid.setHorizontalSpacing(10);
        grid.setVerticalScrollBarEnabled(false);
        grid.setDrawSelectorOnTop(true);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(16);
        ring.setStroke(5, Daily.accent());
        grid.setSelector(ring);
        adapter = new TilesAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, position, id) -> open(position));
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                focused = position < entries.size() ? entries.get(position) : null;
                describe();
                main.removeCallbacks(applyPreview);
                main.postDelayed(applyPreview, 90);   // moving fast through a folder doesn't decode every picture
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        panel.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        empty = text("", 22, 0xCCFFFFFF, Ui.regular);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        panel.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        page.addView(panel, new LinearLayout.LayoutParams(0, -1, 1));
        setContentView(root);
        load(null);
    }

    private TextView text(String s, float size, int color, android.graphics.Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        t.setTextColor(color);
        if (face != null) t.setTypeface(face);
        return t;
    }

    // MARK: - Folders

    private void load(File dir) {
        final int mine = ++generation;
        heading.setText(dir == null ? "Media" : path.size() == 1 ? titleOfRoot : dir.getName());
        empty.setVisibility(View.GONE);
        lister.execute(() -> {
            List<Entry> list = dir == null ? roots() : read(dir);
            main.post(() -> {
                if (mine != generation || isDestroyed()) return;
                entries = list;
                adapter.notifyDataSetChanged();
                empty.setText(dir == null ? "No USB drive found." : "Nothing to play here.");
                empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                Integer at = dir == null ? null : remembered.get(dir.getPath());
                int position = at == null ? 0 : Math.min(at, Math.max(0, list.size() - 1));
                grid.setSelection(position);
                grid.requestFocus();
                focused = position < list.size() ? list.get(position) : null;
                describe();
                showPreview();
            });
        });
    }

    private List<Entry> roots() {
        List<Entry> out = new ArrayList<>();
        File[] mounted = new File("/storage").listFiles();
        int drive = 0;
        if (mounted != null) {
            for (File f : mounted) {
                String n = f.getName();
                if (n.equals("emulated") || n.equals("self") || !f.canRead()) continue;
                drive++;
                out.add(new Entry(f, "USB drive" + (drive > 1 ? " " + drive : ""), "Drive"));
            }
        }
        out.add(new Entry(Environment.getExternalStorageDirectory(), "This TV", "Drive"));
        return out;
    }

    private static List<Entry> read(File dir) {
        List<Entry> folders = new ArrayList<>(), files = new ArrayList<>();
        File[] listed = dir.listFiles();
        if (listed != null) {
            for (File f : listed) {
                String n = f.getName();
                if (n.startsWith(".") || SKIP.contains(n.toLowerCase(Locale.ROOT))) continue;
                if (f.isDirectory()) {
                    folders.add(new Entry(f, n, "Folder"));
                } else {
                    String kind = kind(f);
                    if (kind != null) files.add(new Entry(f, n, kind));
                }
            }
        }
        Collections.sort(folders, (a, b) -> a.title.compareToIgnoreCase(b.title));
        Collections.sort(files, (a, b) -> a.title.compareToIgnoreCase(b.title));
        folders.addAll(files);
        return folders;
    }

    private static String extension(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String kind(File f) {
        String e = extension(f);
        return VIDEO.contains(e) ? "Video" : MUSIC.contains(e) ? "Music" : PHOTO.contains(e) ? "Photo" : null;
    }

    private static String size(long bytes) {
        if (bytes >= 1L << 30) return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
        return Math.max(1, bytes >> 20) + " MB";
    }

    @Override public void onBackPressed() {
        if (path.isEmpty()) {
            super.onBackPressed();
            return;
        }
        path.pop();
        load(path.peek());
    }

    private void open(int position) {
        if (position >= entries.size()) return;
        Entry e = entries.get(position);
        if (!path.isEmpty()) remembered.put(path.peek().getPath(), position);
        switch (e.kind) {
            case "Drive":
                titleOfRoot = e.title;
                path.push(e.file);
                load(e.file);
                break;
            case "Folder":
                path.push(e.file);
                load(e.file);
                break;
            case "Photo":
                showPhoto(position);
                break;
            default:
                play(e);
        }
    }

    private void play(Entry e) {
        String mime = ("Video".equals(e.kind) ? "video/" : "audio/") + "*";
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(e.file), mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (getPackageManager().getLaunchIntentForPackage("org.videolan.vlc") != null) i.setPackage("org.videolan.vlc");
        try {
            startActivity(i);
        } catch (RuntimeException ex) {
            AppLog.w("couldn't play: " + ex.getMessage());
            Toast.makeText(this, "Couldn't play this file", Toast.LENGTH_SHORT).show();
        }
    }

    // MARK: - The preview on the left

    private void describe() {
        if (focused == null) {
            name.setText("");
            detail.setText("");
            return;
        }
        name.setText(focused.title);
        switch (focused.kind) {
            case "Drive": detail.setText("Press OK to open"); break;
            case "Folder": detail.setText("Folder"); break;
            default: detail.setText(focused.kind + "  ·  " + size(focused.file.length()) + "\nPress OK to open");
        }
    }

    private void showPreview() {
        final Entry e = focused;
        if (e == null) {
            preview.setImageDrawable(null);
            return;
        }
        Bitmap ready = memory.get(e.file.getPath());
        preview.setImageBitmap(ready);
        if (ready != null || e.kind.equals("Music")) return;
        preview.setTag(e.file);
        thumbs.execute(() -> {
            Bitmap b = thumbnail(e);
            main.post(() -> {
                if (preview.getTag() == e.file && focused == e) preview.setImageBitmap(b);
            });
        });
    }

    // MARK: - Thumbnails

    private Bitmap thumbnail(Entry e) {
        String key = e.file.getPath();
        Bitmap b = memory.get(key);
        if (b != null) return b;
        File saved = new File(new File(getCacheDir(), "thumbs"), Integer.toHexString(key.hashCode()) + "-" + e.file.lastModified() + ".jpg");
        if (saved.exists()) b = BitmapFactory.decodeFile(saved.getPath());
        if (b == null) {
            try {
                b = makeThumbnail(e);
            } catch (RuntimeException | OutOfMemoryError ex) {
                AppLog.w("no preview for " + e.title + ": " + ex.getMessage());
            }
            if (b != null) {
                //noinspection ResultOfMethodCallIgnored
                saved.getParentFile().mkdirs();
                try (FileOutputStream out = new FileOutputStream(saved)) {
                    b.compress(Bitmap.CompressFormat.JPEG, 82, out);
                } catch (Exception ignored) {
                }
            }
        }
        if (b != null) memory.put(key, b);
        return b;
    }

    private Bitmap makeThumbnail(Entry e) {
        if (e.kind.equals("Photo")) return decode(e.file, THUMB_W, THUMB_W);
        if (e.kind.equals("Video")) {
            Bitmap frame = ThumbnailUtils.createVideoThumbnail(e.file.getPath(), MediaStore.Images.Thumbnails.MINI_KIND);
            return frame;
        }
        if (e.kind.equals("Folder") || e.kind.equals("Drive")) {
            File picture = firstPicture(e.file, 3);
            return picture == null ? null : decode(picture, THUMB_W, THUMB_W);
        }
        return null;
    }

    /** A folder shows the first picture in it, or in the folders inside it. */
    private static File firstPicture(File dir, int depth) {
        File[] inside = dir.listFiles();
        if (inside == null) return null;
        Arrays.sort(inside);
        int looked = 0;
        for (File f : inside) {
            if (++looked > 200) break;
            if (f.isFile() && !f.getName().startsWith(".") && PHOTO.contains(extension(f))) return f;
        }
        if (depth == 0) return null;
        looked = 0;
        for (File f : inside) {
            if (f.isDirectory() && !f.getName().startsWith(".") && !SKIP.contains(f.getName().toLowerCase(Locale.ROOT))) {
                if (++looked > 12) break;
                File found = firstPicture(f, depth - 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Decodes at about the size needed (far faster than the whole camera picture), the right way up. */
    private static Bitmap decode(File f, int w, int h) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        o.inSampleSize = 1;
        while (bounds.outWidth / (o.inSampleSize * 2) >= w && bounds.outHeight / (o.inSampleSize * 2) >= Math.min(h, w / 2)) o.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) return null;
        int turn = 0;
        try {
            int orientation = new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            turn = orientation == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : orientation == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : orientation == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (Exception ignored) {
        }
        if (turn == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(turn);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    }

    private final class TilesAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            FrameLayout tile = (FrameLayout) convert;
            ImageView image;
            TextView label, badge;
            if (tile == null) {
                tile = new FrameLayout(MediaActivity.this);
                tile.setLayoutParams(new AdapterView.LayoutParams(-1, 176));
                image = new ImageView(MediaActivity.this);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setClipToOutline(true);
                GradientDrawable shape = new GradientDrawable();
                shape.setCornerRadius(16);
                shape.setColor(0x26FFFFFF);
                image.setBackground(shape);
                tile.addView(image, new FrameLayout.LayoutParams(-1, -1));
                GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x00000000, 0xCC000000});
                shade.setCornerRadii(new float[] {0, 0, 0, 0, 16, 16, 16, 16});
                label = text("", 17, Color.WHITE, Ui.medium);
                label.setSingleLine(true);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                label.setPadding(12, 26, 12, 10);
                label.setBackground(shade);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
                tile.addView(label, lp);
                badge = text("", 26, Color.WHITE, Ui.semibold);
                badge.setGravity(Gravity.CENTER);
                tile.addView(badge, new FrameLayout.LayoutParams(-1, -1));
                tile.setTag(new View[] {image, label, badge});
            } else {
                View[] parts = (View[]) tile.getTag();
                image = (ImageView) parts[0];
                label = (TextView) parts[1];
                badge = (TextView) parts[2];
            }
            final Entry e = entries.get(position);
            label.setText(e.title);
            final boolean folder = e.kind.equals("Folder") || e.kind.equals("Drive");
            badge.setText(e.kind.equals("Video") ? "▶" : e.kind.equals("Music") ? "♪" : folder ? "📁" : "");
            badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, 34);
            final Bitmap ready = memory.get(e.file.getPath());
            image.setImageBitmap(ready);
            image.setTag(e.file);
            if (ready != null && folder) badge.setText("");
            final TextView mark = badge;
            if (ready == null && !e.kind.equals("Music")) {
                final ImageView target = image;
                thumbs.execute(() -> {
                    if (target.getTag() != e.file) return;   // already scrolled away
                    final Bitmap b = thumbnail(e);
                    if (b != null) main.post(() -> {
                        if (target.getTag() == e.file) {
                            target.setImageBitmap(b);
                            if (folder) mark.setText("");
                        }
                    });
                });
            }
            return tile;
        }
    }

    // MARK: - A photo, full screen

    private void showPhoto(int position) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final ImageView v = new ImageView(this);
        v.setBackgroundColor(Color.BLACK);
        d.setContentView(v);
        final int[] at = {position};
        displayPhoto(v, at[0]);
        d.setOnKeyListener((dialog, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            int step = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
            if (step == 0) return false;
            for (int i = at[0] + step; i >= 0 && i < entries.size(); i += step) {
                if (entries.get(i).kind.equals("Photo")) {
                    at[0] = i;
                    displayPhoto(v, i);
                    grid.setSelection(i);
                    break;
                }
            }
            return true;
        });
        d.show();
    }

    private void displayPhoto(final ImageView v, int position) {
        final Entry e = entries.get(position);
        // The thumbnail appears at once; the sharp picture replaces it a moment later.
        Bitmap quick = memory.get(e.file.getPath());
        v.setImageBitmap(quick);
        v.setTag(e.file);
        fulls.execute(() -> {
            if (v.getTag() != e.file) return;
            Bitmap b;
            try {
                b = decode(e.file, FULL_W, FULL_H);
            } catch (OutOfMemoryError ex) {
                b = null;
            }
            final Bitmap sharp = b;
            main.post(() -> {
                if (v.getTag() != e.file) return;
                if (sharp != null) v.setImageBitmap(sharp);
                else if (v.getDrawable() == null) Toast.makeText(this, "Couldn't open this photo", Toast.LENGTH_SHORT).show();
            });
        });
        // The next one is usually wanted: its thumbnail is made ahead.
        if (position + 1 < entries.size()) {
            final Entry next = entries.get(position + 1);
            if (next.kind.equals("Photo")) thumbs.execute(() -> thumbnail(next));
        }
    }

    @Override protected void onDestroy() {
        generation++;
        main.removeCallbacksAndMessages(null);
        lister.shutdownNow();
        thumbs.shutdownNow();
        fulls.shutdownNow();
        super.onDestroy();
    }
}
