package com.example.livetvapp;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.database.sqlite.SQLiteFullException;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.lang.ref.WeakReference;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

/**
 * Hata günlüğü + kullanıcıya hata kodu gösteren pencere + "Son hatalar" listesi.
 *
 * Kod biçimi: E-ALAN-NEDEN  (örn. E-NET-DNS, E-LISTE-HTTP403, E-OYN-KODEK)
 *
 * - Java çökmelerini yakalar, bir sonraki açılışta gösterir.
 * - Açılışın ilk 20 sn'sinde hata kaydı bırakmadan kapanmayı (native çökme, bellek vb.)
 *   bir sonraki açılışta "E-KAPANMA" olarak gösterir.
 * - Ana iş parçacığı 8 sn'den uzun yanıt vermezse "E-DONMA" kaydeder.
 * - Ağ / liste / veritabanı / dosya / yedek / sunucu / oynatma hatalarını kod ile kaydeder.
 *
 * Pencere açanlar : kullanıcının doğrudan fark edeceği hatalar (açılış, liste, imza, giriş...).
 * Sessiz kaydolanlar: kanal yayın hataları, uzaktan kumanda portu vb. Bunlar yalnızca
 *                    "Son hatalar" listesinde görünür (ölü kanal için her seferinde pencere açılmaz).
 *
 * Kullanıcı adı, şifre ve adres yolları kayıtlardan silinir; yalnızca sunucu adı kalır.
 */
public final class HataGunlugu {

    private static final String TAG = "HataGunlugu";
    private static final String PREFS = "azsh_hata";
    private static final String K_KAYITLAR = "kayitlar";
    private static final String K_BASLADI = "acilis_basladi";
    private static final String K_TAMAM = "acilis_tamam";
    private static final String K_SON_EKRAN = "son_ekran";
    private static final long TAMAM_SURESI_MS = 20_000L;
    private static final long IMZA_OLDURME_MS = 15_000L;
    private static final long DONMA_ESIGI_MS = 8_000L;
    private static final long DEPO_ESIGI_BAYT = 50L * 1024 * 1024;
    private static final int MAX_KAYIT = 30;
    private static final int MAX_OYNATMA_SORGUSU = 5;

    private static Application uygulama;
    private static final Handler ana = new Handler(Looper.getMainLooper());
    private static final Set<String> buOturumdaKaydedilen = new HashSet<>();

    private static WeakReference<Activity> ustEkran = new WeakReference<>(null);
    private static volatile int baslamisEkran = 0;
    private static boolean acilisTamam = false;
    private static volatile long sonTik = 0;

    private static AlertDialog aktifDialog;
    private static Activity dialogSahibi;
    private static boolean kullaniciKapatti;

    private static final AtomicBoolean oynatmaSorgusuSuruyor = new AtomicBoolean(false);
    private static final AtomicInteger oynatmaSorguSayisi = new AtomicInteger(0);

    private static final Pattern HTTP_KODU = Pattern.compile("^HTTP\\s?(\\d{3})");

    private static final Runnable ACILIS_TAMAM = () -> {
        acilisTamam = true;
        try {
            prefs().edit().putBoolean(K_TAMAM, true).apply();
        } catch (Throwable ignored) {
        }
    };

    private HataGunlugu() {
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  BAŞLATMA
    // ═══════════════════════════════════════════════════════════════════════

    /** AzshApplication.onCreate içinden, en başta çağrılır. */
    public static void baslat(Application app) {
        uygulama = app;
        try {
            SharedPreferences sp = prefs();
            boolean oncekiAcilistaKapandi =
                    sp.getBoolean(K_BASLADI, false) && !sp.getBoolean(K_TAMAM, false);
            String sonEkran = sp.getString(K_SON_EKRAN, "?");

            sp.edit().putBoolean(K_BASLADI, false).putBoolean(K_TAMAM, false).commit();

            // E-KAPANMA kaydi kaldirildi: nedeni belirsiz oldugu ve kullaniciyi
            // yanilttigi icin artik kullaniciya gosterilmiyor.
        } catch (Throwable t) {
            Log.w(TAG, "baslat: " + t);
        }

        cokmeYakalayiciKur();
        yasamDongusuKaydet(app);
        depoKontrol(app);
        donmaBekcisiBaslat();
    }

    private static void depoKontrol(Application app) {
        try {
            long bos = app.getFilesDir().getUsableSpace();
            if (bos < DEPO_ESIGI_BAYT) {
                kaydet("E-DEPO-DOLU", "Boş alan: " + (bos / (1024 * 1024)) + " MB");
            }
        } catch (Throwable ignored) {
        }
    }

    private static void cokmeYakalayiciKur() {
        final Thread.UncaughtExceptionHandler eski = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                ekle(cokmeKodu(e),
                        e.getClass().getName() + ": " + e.getMessage() + "\n"
                                + kisalt(Log.getStackTraceString(e), 900),
                        false, false);
                prefs().edit().putBoolean(K_BASLADI, false).commit();
            } catch (Throwable ignored) {
            }
            if (eski != null) {
                eski.uncaughtException(thread, e);
            } else {
                oldur();
            }
        });
    }

    /** Ana iş parçacığı 8 sn'den uzun donarsa, nerede takıldığını kaydeder. */
    private static void donmaBekcisiBaslat() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    if (baslamisEkran <= 0 || Debug.isDebuggerConnected()) {
                        sonTik = 0;
                        continue;
                    }
                    long simdi = SystemClock.uptimeMillis();
                    if (sonTik == 0) {
                        sonTik = simdi;
                    } else if (simdi - sonTik > DONMA_ESIGI_MS) {
                        kaydet("E-DONMA", "Ana iş parçacığı " + ((simdi - sonTik) / 1000)
                                + " sn yanıt vermedi. Takıldığı yer:\n" + anaIsParcacigiYigini());
                    }
                    ana.post(() -> sonTik = SystemClock.uptimeMillis());
                } catch (Throwable ignored) {
                }
            }
        }, "azsh-donma-bekcisi");
        t.setDaemon(true);
        t.start();
    }

    private static String anaIsParcacigiYigini() {
        try {
            StackTraceElement[] st = Looper.getMainLooper().getThread().getStackTrace();
            StringBuilder sb = new StringBuilder();
            int n = Math.min(st.length, 12);
            for (int i = 0; i < n; i++) sb.append(st[i]).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return "(okunamadı)";
        }
    }

    private static void yasamDongusuKaydet(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity a, Bundle b) {
            }

            @Override
            public void onActivityStarted(Activity a) {
                baslamisEkran++;
                try {
                    SharedPreferences.Editor ed = prefs().edit()
                            .putString(K_SON_EKRAN, a.getClass().getSimpleName());
                    if (!acilisTamam) {
                        ed.putBoolean(K_BASLADI, true).commit();
                        ana.removeCallbacks(ACILIS_TAMAM);
                        ana.postDelayed(ACILIS_TAMAM, TAMAM_SURESI_MS);
                    } else {
                        ed.apply();
                    }
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void onActivityResumed(Activity a) {
                ustEkran = new WeakReference<>(a);
                ana.post(HataGunlugu::bekleyenleriGoster);
            }

            @Override
            public void onActivityPaused(Activity a) {
                if (ustEkran.get() == a) ustEkran.clear();
                if (dialogSahibi == a) sessizKapat();
            }

            @Override
            public void onActivityStopped(Activity a) {
                baslamisEkran--;
                if (baslamisEkran <= 0) {
                    baslamisEkran = 0;
                    ana.removeCallbacks(ACILIS_TAMAM);
                    if (!acilisTamam) {
                        try {
                            // Kullanıcı uygulamayı arka plana attı: normal çıkış sayılır.
                            prefs().edit().putBoolean(K_BASLADI, false).apply();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }

            @Override
            public void onActivitySaveInstanceState(Activity a, Bundle b) {
            }

            @Override
            public void onActivityDestroyed(Activity a) {
                if (dialogSahibi == a) sessizKapat();
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  DIŞARIYA AÇIK KAYIT FONKSİYONLARI
    // ═══════════════════════════════════════════════════════════════════════

    /** Genel kayıt + pencere. Aynı kod bir oturumda yalnızca bir kez gösterilir. */
    public static void kaydet(String kod, String detay) {
        kaydetIc(kod, detay, true);
    }

    /** Pencere açmadan yalnızca "Son hatalar" listesine yazar. */
    public static void kaydetSessiz(String kod, String detay) {
        kaydetIc(kod, detay, false);
    }

    private static void kaydetIc(String kod, String detay, boolean pencere) {
        if (uygulama == null) return;
        synchronized (buOturumdaKaydedilen) {
            if (!buOturumdaKaydedilen.add(kod)) return;
        }
        ekle(kod, detay, pencere, !pencere);
    }

    /** ApiHelper (giriş / erişim kontrol sunucusu) ağ hataları. */
    public static void agHatasi(String endpoint, Throwable e) {
        kaydet("E-NET-" + agSonek(e), "İstek: /" + endpoint + " | " + e.getClass().getSimpleName()
                + ": " + e.getMessage());
    }

    /** Giriş sunucusu yanıtı okunamadı (JSON bozuk / HTML döndü). */
    public static void apiYanitHatasi(String endpoint, String govde) {
        kaydet("E-API-YANIT", "İstek: /" + endpoint + " | Yanıt başı: "
                + kisalt(govde == null ? "(boş)" : govde.trim(), 80));
    }

    /** M3U / Xtream liste indirme hataları (yalnızca sunucu adı yazılır). */
    public static void listeIndirmeHatasi(String urlString, Throwable e) {
        sunucuHatasi("LISTE", urlString, e);
    }

    /**
     * Herhangi bir sunucuya bağlanma hatası. Kod: E-{alan}-{neden}.
     * alan örnekleri: LISTE, STK
     */
    public static void sunucuHatasi(String alan, String urlString, Throwable e) {
        String mesaj = e.getMessage();
        if (mesaj != null && urlString != null) mesaj = mesaj.replace(urlString, "[adres]");
        if (e instanceof java.net.MalformedURLException) mesaj = "(adres biçimi hatalı)";
        kaydet("E-" + alan + "-" + agSonek(e), "Sunucu: " + hostCikar(urlString) + " | "
                + e.getClass().getSimpleName() + ": " + mesaj);
    }

    /** Veritabanından kanal listesi okunurken oluşan hatalar. */
    public static void listeHatasi(Exception e) {
        String d = dbSonek(e);
        String kod;
        if (d == null) kod = "E-LISTE-DIGER";
        else if ("DB".equals(d)) kod = "E-LISTE-DB";
        else kod = d;
        kaydet(kod, e.getClass().getName() + ": " + e.getMessage() + "\n"
                + kisalt(Log.getStackTraceString(e), 500));
    }

    /** M3U ayrıştırma sorunları: BOS, BICIM, BELLEK, DOSYA. */
    public static void m3uHatasi(String neden, String detay) {
        kaydet("E-M3U-" + neden, detay);
    }

    /** Dosya okuma/yazma hataları (izin yok, dosya yok, depolama dolu...). */
    public static void dosyaHatasi(String altKod, Throwable e) {
        String k = dosyaSonek(e);
        kaydet(k != null ? k : altKod, e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    /** Yedek alma (yaz=true) / geri yükleme (yaz=false) hataları. */
    public static void yedekHatasi(boolean yaz, Throwable e) {
        String k = dosyaSonek(e);
        if (k == null) {
            boolean json = false;
            for (Throwable c = e; c != null; c = c.getCause()) {
                if (c instanceof JSONException) {
                    json = true;
                    break;
                }
            }
            k = json ? "E-YEDEK-BICIM" : (yaz ? "E-YEDEK-YAZ" : "E-YEDEK-OKU");
        }
        kaydet(k, e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    /** Xtream hesabı teşhisi: GIRIS, SURE. */
    public static void xtreamHatasi(String neden, String detay) {
        kaydet("E-XTR-" + neden, detay);
    }

    /** Stalker portal giriş/token hataları. */
    public static void stalkerTokenHatasi(String detay) {
        kaydet("E-STK-TOKEN", detay);
    }

    /** Uzaktan kumanda / dosya aktarım sunucusu hataları (sessiz). */
    public static void sunucuSoketHatasi(String alan, Throwable e) {
        boolean port = false;
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof java.net.BindException
                    || String.valueOf(c.getMessage()).contains("EADDRINUSE")) {
                port = true;
                break;
            }
        }
        kaydetSessiz(port ? "E-UZAK-PORT" : "E-" + alan + "-AG",
                e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    /** Dosya aktarımı sırasında yazma/boyut hataları (sessiz). */
    public static void aktarimHatasi(Throwable e) {
        String k = dosyaSonek(e);
        kaydetSessiz(k != null ? k : "E-AKTARIM-YAZ",
                e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    /** Oynatıcı (LibVLC) oluşturulamadı. Pencere açılır. */
    public static void oynaticiBaslatmaHatasi(Throwable e) {
        boolean native_ = false;
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof UnsatisfiedLinkError) {
                native_ = true;
                break;
            }
        }
        kaydet(native_ ? "E-NATIVE" : "E-OYN-BASLAT",
                e.getClass().getName() + ": " + e.getMessage());
    }

    /**
     * Yayın açılamadı / zamanında başlamadı. Arka planda kısa bir bağlantı denemesi yapıp
     * nedenini bulmaya çalışır (HTTP kodu, DNS, zaman aşımı...). Sessiz kaydolur.
     *
     * @param zamanAsimi true ise VLC hata vermedi, yayın sadece zamanında başlamadı.
     */
    public static void oynatmaHatasi(final String url, final boolean zamanAsimi) {
        if (uygulama == null || url == null || url.isEmpty()) return;
        if (oynatmaSorguSayisi.get() >= MAX_OYNATMA_SORGUSU) return;
        if (!oynatmaSorgusuSuruyor.compareAndSet(false, true)) return;
        oynatmaSorguSayisi.incrementAndGet();

        Thread t = new Thread(() -> {
            String host = hostCikar(url);
            String kod;
            String detay;
            try {
                if (!url.regionMatches(true, 0, "http", 0, 4)) {
                    String sema = url.contains("://") ? url.substring(0, url.indexOf("://")) : "?";
                    kod = "E-OYN-ACILMADI";
                    detay = "Sunucu: " + host + " | Protokol: " + sema + " (VLC açamadı)";
                } else {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(5000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0");
                    int kodu = c.getResponseCode();
                    c.disconnect();
                    if (kodu >= 400) {
                        kod = "E-OYN-HTTP" + kodu;
                        detay = "Sunucu: " + host + " | HTTP " + kodu;
                    } else if (zamanAsimi) {
                        kod = "E-OYN-ZAMAN";
                        detay = "Sunucu: " + host + " | Bağlantı kuruldu (HTTP " + kodu
                                + ") ama yayın zamanında başlamadı";
                    } else {
                        kod = "E-OYN-KODEK";
                        detay = "Sunucu: " + host + " | Bağlantı sorunsuz (HTTP " + kodu
                                + ") ama VLC yayını açamadı";
                    }
                }
            } catch (Throwable e) {
                kod = "E-OYN-" + agSonek(e);
                detay = "Sunucu: " + host + " | " + e.getClass().getSimpleName();
            }
            kaydetSessiz(kod, detay);
            oynatmaSorgusuSuruyor.set(false);
        }, "azsh-oynatma-teshis");
        t.setDaemon(true);
        t.start();
    }

    /** İmza doğrulama hatası: pencere gösterilir, kullanıcı kapatınca (ya da süre dolunca) uygulama kapanır. */
    public static void imzaHatasi(String kod, String detay) {
        if (uygulama == null) {
            oldur();
            return;
        }
        ekle(kod, detay, true, false);
        ana.postDelayed(HataGunlugu::oldur, IMZA_OLDURME_MS);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  KAYIT DEPOLAMA
    // ═══════════════════════════════════════════════════════════════════════

    private static SharedPreferences prefs() {
        return uygulama.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static synchronized void ekle(String kod, String detay, boolean hemenGoster, boolean goruldu) {
        if (uygulama == null) return;
        try {
            SharedPreferences sp = prefs();
            JSONArray arr = oku(sp);
            JSONObject o = new JSONObject();
            o.put("kod", kod);
            o.put("detay", gizle(kisalt(detay, 900)));
            o.put("zaman", System.currentTimeMillis());
            o.put("cihaz", cihazBilgisi());
            o.put("goruldu", goruldu);
            arr.put(o);
            while (arr.length() > MAX_KAYIT) arr.remove(0);
            sp.edit().putString(K_KAYITLAR, arr.toString()).commit();
        } catch (Throwable t) {
            Log.w(TAG, "ekle: " + t);
        }
        if (hemenGoster) ana.post(HataGunlugu::bekleyenleriGoster);
    }

    private static JSONArray oku(SharedPreferences sp) {
        try {
            return new JSONArray(sp.getString(K_KAYITLAR, "[]"));
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    private static synchronized JSONObject ilkGorulmemis() {
        try {
            JSONArray arr = oku(prefs());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                // Eski surumlerden kalan E-KAPANMA kayitlari pencerede gosterilmez.
                if (!o.optBoolean("goruldu", false) && !"E-KAPANMA".equals(o.optString("kod"))) return o;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static synchronized void gorulduIsaretle(long zaman) {
        try {
            SharedPreferences sp = prefs();
            JSONArray arr = oku(sp);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.optLong("zaman") == zaman) o.put("goruldu", true);
            }
            sp.edit().putString(K_KAYITLAR, arr.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PENCERELER
    // ═══════════════════════════════════════════════════════════════════════

    private static void bekleyenleriGoster() {
        Activity a = ustEkran.get();
        if (a == null || a.isFinishing() || a.isDestroyed() || aktifDialog != null) return;
        JSONObject k = ilkGorulmemis();
        if (k == null) return;
        goster(a, k);
    }

    private static String govdeMetni(JSONObject k) {
        StringBuilder sb = new StringBuilder();
        sb.append(aciklama(k.optString("kod", "E-?"))).append("\n\n");
        sb.append("Detay: ").append(k.optString("detay", "-")).append("\n\n");
        sb.append("Cihaz: ").append(k.optString("cihaz", "-")).append("\n");
        sb.append("Kayıt zamanı: ").append(zamanMetni(k.optLong("zaman")));
        return sb.toString();
    }

    private static String zamanMetni(long zaman) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(zaman));
    }

    private static void goster(final Activity a, JSONObject k) {
        final String kod = k.optString("kod", "E-?");
        final long zaman = k.optLong("zaman");
        final boolean olumcul = kod.startsWith("E-IMZA");

        try {
            // Kullanıcı kodu okuyup iletecek: pencerede yalnızca büyük harflerle kod görünür.
            // Ayrıntılar (açıklama, cihaz bilgisi) Ayarlar > Hata Kayıtları'nda.
            android.widget.TextView kodYazisi = new android.widget.TextView(a);
            kodYazisi.setText(kod);
            kodYazisi.setTextSize(36f);
            kodYazisi.setTextColor(0xFFFFFFFF);
            kodYazisi.setGravity(android.view.Gravity.CENTER);
            kodYazisi.setPadding(48, 40, 48, 40);

            AlertDialog.Builder b = new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
            b.setTitle("Hata kodu");
            b.setView(kodYazisi);
            b.setCancelable(!olumcul);
            b.setPositiveButton("Tamam", (d, w) -> kullaniciKapatti = true);

            final AlertDialog d = b.create();
            d.setOnCancelListener(x -> kullaniciKapatti = true);
            d.setOnDismissListener(x -> {
                aktifDialog = null;
                dialogSahibi = null;
                if (kullaniciKapatti) {
                    gorulduIsaretle(zaman);
                    if (olumcul) {
                        oldur();
                    } else {
                        ana.post(HataGunlugu::bekleyenleriGoster);
                    }
                }
            });

            kullaniciKapatti = false;
            aktifDialog = d;
            dialogSahibi = a;
            d.show();

            Button tamam = d.getButton(AlertDialog.BUTTON_POSITIVE);
            if (tamam != null) tamam.requestFocus();   // TV kumandası ile hemen seçilebilsin
        } catch (Throwable t) {
            aktifDialog = null;
            dialogSahibi = null;
            Log.w(TAG, "pencere gösterilemedi: " + t);
        }
    }

    private static void panoyaKopyala(Activity a, String metin) {
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("hata", metin));
                Toast.makeText(a, "Kopyalandı", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Ayarlar menüsünden çağrılır: kayıtlı son hataları listeler (yeniden eskiye). */
    public static void sonHatalariGoster(final Activity a) {
        try {
            JSONArray arr = oku(prefs());
            if (arr.length() == 0) {
                new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                        .setTitle("Son hatalar")
                        .setMessage("Kayıtlı hata yok.")
                        .setPositiveButton("Tamam", null)
                        .show();
                return;
            }
            final int n = arr.length();
            final JSONObject[] kayitlar = new JSONObject[n];
            final String[] satirlar = new String[n];
            for (int i = 0; i < n; i++) {
                JSONObject o = arr.getJSONObject(n - 1 - i);   // en yeni üstte
                kayitlar[i] = o;
                satirlar[i] = new SimpleDateFormat("dd.MM HH:mm", Locale.US)
                        .format(new Date(o.optLong("zaman"))) + "   " + o.optString("kod", "E-?");
            }
            AlertDialog.Builder b = new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
            b.setTitle("Son hatalar (" + n + ")");
            b.setItems(satirlar, (d, which) -> detayPenceresi(a, kayitlar[which]));
            b.setPositiveButton("Kapat", null);
            b.setNeutralButton("Temizle", (d, w) -> {
                try {
                    prefs().edit().remove(K_KAYITLAR).apply();
                    Toast.makeText(a, "Hata kayıtları silindi", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            });
            b.show();
        } catch (Throwable t) {
            Log.w(TAG, "sonHatalariGoster: " + t);
        }
    }

    private static void detayPenceresi(final Activity a, JSONObject k) {
        final String kod = k.optString("kod", "E-?");
        final String tamMetin = "Hata kodu: " + kod + "\n" + govdeMetni(k);
        try {
            AlertDialog d = new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle("Hata kodu: " + kod)
                    .setMessage(govdeMetni(k))
                    .setPositiveButton("Tamam", null)
                    .setNeutralButton("Kopyala", null)
                    .create();
            d.show();
            Button kopyala = d.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (kopyala != null) kopyala.setOnClickListener(v -> panoyaKopyala(a, tamMetin));
        } catch (Throwable ignored) {
        }
    }

    private static void sessizKapat() {
        if (aktifDialog != null) {
            kullaniciKapatti = false;
            try {
                aktifDialog.dismiss();
            } catch (Throwable ignored) {
            }
            aktifDialog = null;
            dialogSahibi = null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  KOD AÇIKLAMALARI
    // ═══════════════════════════════════════════════════════════════════════

    private static String aciklama(String kod) {
        switch (kod) {
            // Başlatma / sistem
            case "E-IMZA-1":
                return "Uygulamanın imzası doğrulanamadı. Lütfen uygulamayı resmi kaynaktan yeniden kurun.";
            case "E-IMZA-2":
                return "Uygulama imzası okunamadı. Cihaz yazılımı imza bilgisini vermiyor olabilir.";
            case "E-KAPANMA":
                return "Uygulama önceki açılışta beklenmedik şekilde kapandı. "
                        + "Bellek yetersizliği veya cihaz uyumsuzluğu olabilir.";
            case "E-DONMA":
                return "Uygulama bir süre yanıt vermedi (takıldı). Cihaz yavaş olabilir ya da "
                        + "büyük bir liste işlenirken donmuş olabilir.";
            case "E-DEPO-DOLU":
                return "Cihazın depolama alanı dolu veya çok az kaldı. Yer açıp tekrar deneyin.";
            case "E-NATIVE":
                return "Oynatıcı (VLC) bileşeni yüklenemedi. Cihaz mimarisi/yazılımı uyumsuz olabilir.";
            case "E-BELLEK":
                return "Cihazın belleği yetmedi.";
            case "E-VERITABANI":
                return "Veritabanı hatası oluştu.";
            case "E-IZIN":
                return "Gerekli bir izin verilmedi veya cihaz izni reddetti.";
            case "E-COKME":
                return "Uygulama beklenmedik bir hata nedeniyle kapandı.";

            // Giriş sunucusu
            case "E-API-YANIT":
                return "Sunucudan beklenmeyen bir yanıt geldi (okunamadı). Ağ geçidi/modem sayfası "
                        + "araya girmiş olabilir.";

            // Liste / M3U / Xtream / Stalker
            case "E-LISTE-DB":
                return "Kanal listesi veritabanından okunamadı.";
            case "E-LISTE-DIGER":
                return "Kanal listesi yüklenirken hata oluştu.";
            case "E-M3U-BOS":
                return "Liste boş geldi (içinde kanal yok). Adres, hesap süresi veya sağlayıcı kısıtı kontrol edilmeli.";
            case "E-M3U-BICIM":
                return "Gelen içerik geçerli bir M3U listesi değil (#EXTM3U başlığı yok). "
                        + "Adres yanlış olabilir ya da sağlayıcı farklı bir sayfa döndürüyor.";
            case "E-M3U-BELLEK":
                return "Liste çok büyük, cihazın belleği işlemeye yetmedi.";
            case "E-M3U-DOSYA":
                return "M3U dosyası açılamadı veya okunamadı.";
            case "E-XTR-GIRIS":
                return "Xtream hesabı giriş bilgileri sunucu tarafından kabul edilmedi "
                        + "(kullanıcı adı/şifre hatalı ya da hesap devre dışı).";
            case "E-XTR-SURE":
                return "Xtream hesabının abonelik süresi dolmuş.";
            case "E-STK-TOKEN":
                return "Stalker portalından oturum anahtarı (token) alınamadı. MAC adresi veya portal adresi hatalı olabilir.";

            // Oynatma
            case "E-OYN-BASLAT":
                return "Oynatıcı oluşturulamadı.";
            case "E-OYN-KODEK":
                return "Sunucuya erişim sorunsuz ama yayın açılamadı. Yayın biçimi veya donanım çözücü "
                        + "cihazla uyumsuz olabilir.";
            case "E-OYN-ACILMADI":
                return "Yayın adresi VLC tarafından açılamadı (desteklenmeyen protokol olabilir).";

            // Veritabanı / depolama / yedek / dosya
            case "E-DB-BOZUK":
                return "Veritabanı dosyası bozuk görünüyor. Uygulama verisini temizlemek gerekebilir.";
            case "E-DB-KILIT":
                return "Veritabanı meşgul/kilitli. Cihaz yavaş olabilir.";
            case "E-YEDEK-YAZ":
                return "Yedek dosyası oluşturulamadı.";
            case "E-YEDEK-OKU":
                return "Yedek dosyası okunamadı.";
            case "E-YEDEK-BICIM":
                return "Yedek dosyası bozuk ya da bu uygulamaya ait değil.";
            case "E-DOSYA-IZIN":
                return "Depolama izni yok veya cihaz dosya erişimini engelledi.";
            case "E-DOSYA-YOK":
                return "Dosya veya klasör bulunamadı.";

            // Uzaktan kumanda / aktarım
            case "E-UZAK-PORT":
                return "Uzaktan kumanda/aktarım portu başka bir uygulama tarafından kullanılıyor.";
            case "E-UZAK-AG":
            case "E-AKTARIM-AG":
                return "Yerel ağ sunucusu başlatılamadı (Wi-Fi/ağ izni olmayabilir).";
            case "E-AKTARIM-YAZ":
                return "Aktarılan dosya cihaza yazılamadı.";
            default:
                break;
        }

        // E-ALAN-SONEK biçimindeki ağ kodları
        String on;
        if (kod.startsWith("E-LISTE-")) on = "Liste sunucusu: ";
        else if (kod.startsWith("E-STK-")) on = "Stalker portalı: ";
        else if (kod.startsWith("E-OYN-")) on = "Yayın sunucusu: ";
        else on = "Uygulama sunucusu: ";

        String sonek = kod.substring(kod.lastIndexOf('-') + 1);
        switch (sonek) {
            case "YOK":
                return "İnternet bağlantısı yok.";
            case "DNS":
                return on + "adres çözülemedi (DNS). Modem/DNS ayarı veya sunucu adresi engelleniyor olabilir.";
            case "SAAT":
                return "Cihaz saati/tarihi hatalı olabilir; güvenli bağlantı kurulamadı.";
            case "GUVEN":
                return "Cihazda güvenli bağlantı için gereken sertifika yok ya da eski (eski Android sürümü).";
            case "PIN":
                return "Sunucu sertifikası beklenenle eşleşmedi.";
            case "SSL":
                return on + "güvenli bağlantı (SSL) kurulamadı.";
            case "ZAMAN":
                return on + "zamanında yanıt vermedi.";
            case "BAGLANTI":
                return on + "bağlantı kurulamadı.";
            default:
                break;
        }
        if (sonek.startsWith("HTTP")) {
            String sayi = sonek.substring(4);
            if (kod.startsWith("E-OYN-")) {
                if ("404".equals(sayi)) return "Yayın bulunamadı (HTTP 404). Kanal kaldırılmış olabilir.";
                if ("401".equals(sayi) || "403".equals(sayi))
                    return "Sağlayıcı yayına erişimi reddetti (HTTP " + sayi + "): abonelik, "
                            + "eş zamanlı bağlantı sınırı veya IP engeli olabilir.";
                if (sayi.startsWith("5"))
                    return "Sağlayıcı sunucusu hata veriyor (HTTP " + sayi + ").";
            }
            if (("401".equals(sayi) || "403".equals(sayi)) && !kod.startsWith("E-NET-"))
                return on + "erişimi reddetti (HTTP " + sayi + "): kullanıcı bilgisi hatalı, "
                        + "hesap süresi dolmuş veya IP engellenmiş olabilir.";
            return on + "hata kodu döndürdü (HTTP " + sayi + ").";
        }
        return on + "bağlantı hatası.";
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  KOD HESAPLAMA
    // ═══════════════════════════════════════════════════════════════════════

    private static String cokmeKodu(Throwable e) {
        String db = dbSonek(e);
        if (db != null) return "DB".equals(db) ? "E-VERITABANI" : db;
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof UnsatisfiedLinkError) return "E-NATIVE";
            if (c instanceof OutOfMemoryError) return "E-BELLEK";
            if (c instanceof SecurityException) return "E-IZIN";
        }
        return "E-COKME";
    }

    /**
     * Veritabanı kaynaklı hata ise kodu, değilse null döner.
     * "DB" = genel veritabanı hatası.
     */
    private static String dbSonek(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            String ad = c.getClass().getName();
            if (!(ad.startsWith("android.database") || ad.startsWith("androidx.room"))) continue;
            String m = String.valueOf(c.getMessage()).toLowerCase(Locale.ROOT);
            if (c instanceof SQLiteFullException || m.contains("disk is full")) return "E-DEPO-DOLU";
            if (c instanceof SQLiteDatabaseCorruptException || m.contains("malformed")
                    || m.contains("corrupt")) return "E-DB-BOZUK";
            if (m.contains("locked") || m.contains("busy")) return "E-DB-KILIT";
            return "DB";
        }
        return null;
    }

    /** Dosya hatası türünü bulur; tanınmazsa null. */
    private static String dosyaSonek(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            String m = String.valueOf(c.getMessage());
            String k = m.toLowerCase(Locale.ROOT);
            if (c instanceof SecurityException || m.contains("EACCES") || k.contains("permission denied"))
                return "E-DOSYA-IZIN";
            if (m.contains("ENOSPC") || k.contains("no space left") || k.contains("disk is full"))
                return "E-DEPO-DOLU";
            if (c instanceof FileNotFoundException || m.contains("ENOENT") || k.contains("no such file"))
                return "E-DOSYA-YOK";
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static String agSonek(Throwable e) {
        try {
            ConnectivityManager cm = (ConnectivityManager) uygulama.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return "YOK";
        } catch (Throwable ignored) {
        }
        // Önce özel durumlar (zincirin tamamında), sonra genel SSL
        for (Throwable c = e; c != null; c = c.getCause()) {
            String m = String.valueOf(c.getMessage());
            if (c instanceof UnknownHostException) return "DNS";
            if (c instanceof CertificateNotYetValidException || c instanceof CertificateExpiredException)
                return "SAAT";
            if (m.contains("SSL Pinning")) return "PIN";
            if (m.contains("Trust anchor") || c instanceof CertPathValidatorException) return "GUVEN";
            if (c instanceof SocketTimeoutException) return "ZAMAN";
            if (c instanceof ConnectException || c instanceof NoRouteToHostException) return "BAGLANTI";
        }
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof SSLException) return "SSL";
        }
        String m = e.getMessage();
        if (m != null) {
            Matcher mt = HTTP_KODU.matcher(m);
            if (mt.find()) return "HTTP" + mt.group(1);
        }
        return "DIGER";
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  YARDIMCILAR
    // ═══════════════════════════════════════════════════════════════════════

    private static String hostCikar(String urlString) {
        try {
            String h = new URL(urlString).getHost();
            return (h == null || h.isEmpty()) ? "?" : h;
        } catch (Throwable ignored) {
            return "?";
        }
    }

    @SuppressWarnings("deprecation")
    private static String cihazBilgisi() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append(" | Android ").append(Build.VERSION.RELEASE)
                    .append(" (API ").append(Build.VERSION.SDK_INT).append(')')
                    .append(" | ABI: ").append(TextUtils.join(",", Build.SUPPORTED_ABIS));
        } catch (Throwable ignored) {
        }
        try {
            ActivityManager am = (ActivityManager) uygulama.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            sb.append(" | RAM: ").append(mi.totalMem / (1024 * 1024)).append(" MB");
            if (am.isLowRamDevice()) sb.append(" (düşük RAM)");
        } catch (Throwable ignored) {
        }
        try {
            long bos = uygulama.getFilesDir().getUsableSpace();
            sb.append(" | Boş alan: ").append(bos / (1024 * 1024)).append(" MB");
        } catch (Throwable ignored) {
        }
        try {
            PackageInfo pi = uygulama.getPackageManager().getPackageInfo(uygulama.getPackageName(), 0);
            sb.append(" | Uygulama: ").append(pi.versionName).append(" (").append(pi.versionCode).append(')');
        } catch (Throwable ignored) {
        }
        try {
            sb.append(" | Saat: ")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()))
                    .append(' ').append(TimeZone.getDefault().getID());
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static String kisalt(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * Kullanıcı adı / şifre / token ve adres yollarını detaydan siler:
     * - "http://kullanici:sifre@host" → "http://host"
     * - "http://host/herhangi/yol?x=1" → "http://host/…"
     * - username= / password= / token= değerleri → ***
     */
    private static String gizle(String s) {
        if (s == null) return "";
        String r = s.replaceAll("(?i)(username|password|user|pass|token)=[^&\\s\"']+", "$1=***");
        r = r.replaceAll("(?i)(https?://)[^/@\\s\"']+@", "$1");
        r = r.replaceAll("(?i)(https?://[^/\\s\"']+)/[^\\s\"']*", "$1/…");
        return r;
    }

    private static void oldur() {
        try {
            // Bilinçli kapanış: bir sonraki açılışta "E-KAPANMA" olarak görünmesin.
            prefs().edit().putBoolean(K_BASLADI, false).commit();
        } catch (Throwable ignored) {
        }
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(1);
    }
}