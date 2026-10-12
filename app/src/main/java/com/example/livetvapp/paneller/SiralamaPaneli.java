package com.example.livetvapp.paneller;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.livetvapp.Channel;
import com.example.livetvapp.R;
import com.example.livetvapp.Adapter.SiralamaAdapter;
import com.example.livetvapp.database.AppDatabase;
import com.example.livetvapp.database.KategoriItem;
import com.example.livetvapp.database.M3UManager;
import com.example.livetvapp.database.M3UItem;
import com.example.livetvapp.database.SiralamaItem;
import com.example.livetvapp.database.FavorilerYoneticisi;
import java.util.Set;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
public class SiralamaPaneli {
    private static final String[] TUR_KODLARI   = {"LIVE",      "MOVIE",   "SERIES"};
    private static final String[] TUR_ETIKETLER = {"📺 Canlı TV", "🎬 Film", "📺 Dizi"};
    private static final String PREFS_SIRALAMA = "kategori_siralama";
    private static final String KAYIT_AYRAC = "###";
    private boolean dialogAcik = false;
    private boolean degisiklikVar = false;
    private AppCompatActivity activity;
    private View          panelView;
    private RecyclerView  recyclerView;
    private SiralamaAdapter adapter;
    private Handler       mainHandler;
    private M3UManager    m3uManager;
    public interface PanelKapandiCallback { void onKapandi(); }
    private PanelKapandiCallback kapandiCallback;
    public void setPanelKapandiCallback(PanelKapandiCallback cb) { this.kapandiCallback = cb; }
    public interface KategoriGuncelleCallback {
        // Her öğe kategori adı + hangi M3U'dan geldiği (kaynakAdi) bilgisini taşır
        void onKategoriSirasiDegisti(String turKodu, List<KategoriItem> yeniSira);
    }
    private KategoriGuncelleCallback kategoriGuncelleCallback;
    public void setKategoriGuncelleCallback(KategoriGuncelleCallback cb) {
        this.kategoriGuncelleCallback = cb;
    }
    // Kanal (veya favori) sırası değişince ana ekrandaki kanal listesine haber verir.
    // Panel kapanınca (Evet/Hayır sonrası) tür + kategori başına bir kez çağrılır.
    public interface KanalSirasiCallback {
        void onKanalSirasiDegisti(String turKodu, String kategoriAdi);
    }
    private KanalSirasiCallback kanalSirasiCallback;
    public void setKanalSirasiCallback(KanalSirasiCallback cb) {
        this.kanalSirasiCallback = cb;
    }
    // "TUR|Kategori" anahtarları; arka plan thread'lerinden de doldurulur, o yüzden senkronize kullanılır
    private final LinkedHashSet<String> bekleyenKanalSirasi = new LinkedHashSet<>();
    private boolean geriAlmaSuruyor = false;
    private final List<SiralamaItem> flatListe = new ArrayList<>();
    private final Map<String, List<KategoriGirisi>> tumKategoriler = new LinkedHashMap<>();
    private final Map<String, List<Channel>> tumKanallar = new LinkedHashMap<>();
    private final Map<String, List<String>> favoriDiziAdlari = new LinkedHashMap<>();
    private boolean veriYuklendi = false;
    private final Map<String, String>  siralamaSnapshot = new LinkedHashMap<>();
    private final Map<Long, Integer> kanalPozSnapshot = new LinkedHashMap<>();
    // Tür -> gizli olanlar DAHİL tam kategori sırası ("m3u###kategori" anahtarları)
    private final Map<String, List<String>> tamKategoriSirasi = new LinkedHashMap<>();
    public SiralamaPaneli(AppCompatActivity activity) {
        this.activity    = activity;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.m3uManager  = new M3UManager(activity);
    }
    public void olustur() {
        System.out.println("🟠 SP.olustur() ÇAĞRILDI");
        panelView    = LayoutInflater.from(activity).inflate(R.layout.panel_siralama, null);
        recyclerView = panelView.findViewById(R.id.siralamaRecyclerView);
        panelView.setVisibility(View.GONE);
        LinearLayoutManager lm = new LinearLayoutManager(activity) {
            @Override
            public boolean requestChildRectangleOnScreen(
                    RecyclerView parent, View child,
                    android.graphics.Rect rect, boolean immediate,
                    boolean focusedChildVisible) {
                int parentHeight = parent.getHeight();
                int childTop     = child.getTop();
                int childBottom  = child.getBottom();
                int childCenter  = (childTop + childBottom) / 2;
                int parentCenter = parentHeight / 2;
                int itemCount = getItemCount();
                int pos       = parent.getChildAdapterPosition(child);
                int childH    = child.getHeight() > 0 ? child.getHeight() : 1;
                boolean sonlaraYakin = pos >= itemCount - parentHeight / childH / 2;
                if (sonlaraYakin) {
                    return super.requestChildRectangleOnScreen(
                            parent, child, rect, immediate, focusedChildVisible);
                }
                int kaydirma = childCenter - parentCenter;
                if (kaydirma == 0) return false;
                if (immediate) parent.scrollBy(0, kaydirma);
                else           parent.smoothScrollBy(0, kaydirma);
                return true;
            }
        };
        recyclerView.setLayoutManager(lm);
        adapter = new SiralamaAdapter();
        recyclerView.setAdapter(adapter);
        adapter.setAcKapatCallback(pos -> acKapat(pos));
        adapter.setTasimaCallback(hedefPos -> tasi(hedefPos));
        adapter.setSagTusCallback(pos -> {
            List<SiralamaItem> gor = adapter.getListe();
            if (pos < 0 || pos >= gor.size()) return;
            SiralamaItem item = gor.get(pos);
            if (item.getTip() == SiralamaItem.TIP_TUR) return;
            if (item.getTip() == SiralamaItem.TIP_KATEGORI
                    && FavorilerYoneticisi.FAVORI_KATEGORI_ADI.equals(item.getAd())) return;
            item.setSecili(true);
            adapter.notifyItemChanged(pos);
        });
        adapter.setSolTusCallback(pos -> {
            List<SiralamaItem> gor = adapter.getListe();
            if (pos < 0 || pos >= gor.size()) return;
            gor.get(pos).setSecili(false);
            adapter.notifyItemChanged(pos);
        });
        activity.addContentView(
                panelView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
    }
    public void ac() {
        degisiklikVar = false;
        if (veriYuklendi) {
            veriYuklendi = false;
            tumKategoriler.clear();
            tumKanallar.clear();
            favoriDiziAdlari.clear();
            flatListeyiSifirla();
            adapter.setTumListe(new ArrayList<>(flatListe));
        }
        panelView.setVisibility(View.VISIBLE);
        flatListeyiSifirla();
        adapter.setTumListe(new ArrayList<>(flatListe));
        siralamaSnapshot.clear();
        kanalPozSnapshot.clear();
        android.content.SharedPreferences snapPrefs =
                activity.getSharedPreferences(PREFS_SIRALAMA, Context.MODE_PRIVATE);
        for (String tk : TUR_KODLARI) {
            String k = tk + "_sirasi";
            siralamaSnapshot.put(k, snapPrefs.getString(k, null));
        }
        if (!veriYuklendi) {
            dbdenYukle();
        }
        recyclerView.postDelayed(() -> {
            RecyclerView.ViewHolder vh = recyclerView.findViewHolderForAdapterPosition(0);
            if (vh != null) vh.itemView.requestFocus();
        }, 150);
    }
    public void kapat() {
        dialogAcik = false;
        adapter.secimleriTemizle();
        flatListeyiSifirla();
        panelView.setVisibility(View.GONE);
        panelView.clearFocus();
        veriYuklendi = false;
        tumKategoriler.clear();
        tamKategoriSirasi.clear();
        tumKanallar.clear();
        favoriDiziAdlari.clear();
        siralamaSnapshot.clear();
        kanalPozSnapshot.clear();
        System.out.println("🧹 SiralamaPaneli cache temizlendi");
        if (geriAlmaSuruyor) {
            // "Hayır" dendi: DB'deki eski pozisyonlar geri yazılıyor, bildirim o iş bitince gidecek
            System.out.println("[SIRA-LOG] KAPAT: geri alma sürüyor, ana liste bildirimi geri alma bitince gönderilecek");
        } else {
            kanalSirasiBildir("kapat");
        }
        if (kapandiCallback != null) kapandiCallback.onKapandi();
    }
    private void kanalSirasiIsaretle(String turKodu, String kategoriAdi) {
        if (turKodu == null || kategoriAdi == null) return;
        synchronized (bekleyenKanalSirasi) {
            bekleyenKanalSirasi.add(turKodu + "|" + kategoriAdi);
        }
        System.out.println("[SIRA-LOG] ANA-BİLDİRİM işaretlendi: " + turKodu + "|" + kategoriAdi);
    }
    private void kanalSirasiBildir(String kaynak) {
        final List<String> gonderilecek;
        synchronized (bekleyenKanalSirasi) {
            gonderilecek = new ArrayList<>(bekleyenKanalSirasi);
            bekleyenKanalSirasi.clear();
        }
        System.out.println("[SIRA-LOG] ANA-BİLDİRİM gönderiliyor (" + kaynak + "): "
                + gonderilecek.size() + " kayıt " + gonderilecek
                + (kanalSirasiCallback == null ? " (callback YOK)" : ""));
        if (kanalSirasiCallback == null) return;
        for (String anahtar : gonderilecek) {
            int ayrac = anahtar.indexOf('|');
            if (ayrac < 0) continue;
            final String tur = anahtar.substring(0, ayrac);
            final String kat = anahtar.substring(ayrac + 1);
            mainHandler.post(() -> kanalSirasiCallback.onKanalSirasiDegisti(tur, kat));
        }
    }
    private void dbdenYukle() {
        new Thread(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(activity);
                List<M3UItem> m3uListesi = m3uManager.getAllM3UItems();
                Set<String> gizliKategoriler = GizlePaneli.getGizliKategoriler(activity);
                Set<String> gizliKanalUrlleri = GizlePaneli.getGizliKanalUrlleri(activity);
                for (String turKodu : TUR_KODLARI) {
                    List<KategoriGirisi> katListesi = new ArrayList<>();
                    List<String> kayitliSira = kaydedilenSirayiYukle(turKodu);
                    List<KategoriGirisi> hepsiGirisleri = new ArrayList<>();
                    if (m3uListesi != null && !m3uListesi.isEmpty()) {
                        List<KategoriGirisi> tumKatGirisleri = new ArrayList<>();
                        for (M3UItem m3u : m3uListesi) {
                            List<String> kats = db.channelDao().getCategoriesByType(m3u.getName(), turKodu);
                            for (String kat : kats) {
                                String gizleKey = m3u.getName() + "|" + kat;
                                hepsiGirisleri.add(new KategoriGirisi(kat, m3u.getName()));
                                if (!gizliKategoriler.contains(gizleKey)) {
                                    tumKatGirisleri.add(new KategoriGirisi(kat, m3u.getName()));
                                }
                            }
                        }
                        KategoriGirisi favoriGirisi = new KategoriGirisi(
                                FavorilerYoneticisi.FAVORI_KATEGORI_ADI, "");
                        katListesi.add(favoriGirisi);
                        if (kayitliSira != null && !kayitliSira.isEmpty()) {
                            for (String kayitliKey : kayitliSira) {
                                String[] parcalar = kayitliKey.split(KAYIT_AYRAC);
                                if (parcalar.length == 2) {
                                    String kayitliM3u = parcalar[0];
                                    String kayitliKat = parcalar[1];
                                    for (KategoriGirisi kg : tumKatGirisleri) {
                                        if (kg.ad.equals(kayitliKat) && kg.m3uAdi.equals(kayitliM3u)) {
                                            katListesi.add(kg);
                                            break;
                                        }
                                    }
                                }
                            }
                            for (KategoriGirisi kg : tumKatGirisleri) {
                                boolean var = false;
                                for (KategoriGirisi mevcut : katListesi) {
                                    if (mevcut.ad.equals(kg.ad) && mevcut.m3uAdi.equals(kg.m3uAdi)) {
                                        var = true;
                                        break;
                                    }
                                }
                                if (!var) katListesi.add(kg);
                            }
                        } else {
                            katListesi.addAll(tumKatGirisleri);
                        }
                    }
                    tumKategoriler.put(turKodu, katListesi);
                    // Gizli kategoriler dahil tam sıra (kayıt sırasında yerlerini korumak için)
                    List<String> tamSira = new ArrayList<>();
                    if (kayitliSira != null) {
                        for (String kk : kayitliSira) {
                            for (KategoriGirisi kg : hepsiGirisleri) {
                                if ((kg.m3uAdi + KAYIT_AYRAC + kg.ad).equals(kk)) { tamSira.add(kk); break; }
                            }
                        }
                    }
                    for (KategoriGirisi kg : hepsiGirisleri) {
                        String kk = kg.m3uAdi + KAYIT_AYRAC + kg.ad;
                        if (!tamSira.contains(kk)) tamSira.add(kk);
                    }
                    tamKategoriSirasi.put(turKodu, tamSira);
                    System.out.println("[SIRA-LOG] YÜKLEME kategori tam sıra [" + turKodu + "] (*=gizli): "
                            + katLogu(tamSira, gizliKategoriler));
                }
                veriYuklendi = true;
                System.out.println("✅ SiralamaPaneli DB yükleme tamamlandı");
            } catch (Exception e) {
                System.out.println("❌ SiralamaPaneli DB yükleme hatası: " + e.getMessage());
            }
        }).start();
    }
    private void kanallarıYukle(String turKodu, String kategoriAdi, Runnable tamamCallback) {
        String key = turKodu + "|" + kategoriAdi;
        if (tumKanallar.containsKey(key)) {
            if (tamamCallback != null) mainHandler.post(tamamCallback);
            return;
        }
        if (FavorilerYoneticisi.FAVORI_KATEGORI_ADI.equals(kategoriAdi)) {
            FavorilerYoneticisi fy = FavorilerYoneticisi.getInstance(activity);
            List<String> anahtarlar = fy.getHepsi(turKodu);
            if (anahtarlar.isEmpty()) {
                tumKanallar.put(key, new ArrayList<>());
                if (tamamCallback != null) mainHandler.post(tamamCallback);
                return;
            }
            new Thread(() -> {
                try {
                    AppDatabase db = AppDatabase.getInstance(activity);
                    Set<String> gizliUrller = GizlePaneli.getGizliKanalUrlleri(activity);
                    List<Channel> sirali = new ArrayList<>();
                    if ("SERIES".equals(turKodu)) {
                        favoriDiziAdlari.put(turKodu, new ArrayList<>(anahtarlar));
                    } else {
                        for (String url : anahtarlar) {
                            Channel ch = db.channelDao().getChannelByUrl(url);
                            if (ch != null && !gizliUrller.contains(ch.getUrl())) {
                                sirali.add(ch);
                                if (!kanalPozSnapshot.containsKey(ch.getId())) {
                                    kanalPozSnapshot.put(ch.getId(), ch.getPosition());
                                }
                            }
                        }
                    }
                    tumKanallar.put(key, sirali);
                } catch (Exception e) {
                    tumKanallar.put(key, new ArrayList<>());
                }
                if (tamamCallback != null) mainHandler.post(tamamCallback);
            }).start();
            return;
        }
        new Thread(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(activity);
                List<Channel> hepsi = db.channelDao()
                        .getChannelsByTypeAndCategoryAllM3U(turKodu, kategoriAdi);
                Set<String> gizliUrller = GizlePaneli.getGizliKanalUrlleri(activity);
                List<Channel> gorunenler = new ArrayList<>();
                if (hepsi != null) {
                    for (Channel ch : hepsi) {
                        if (!gizliUrller.contains(ch.getUrl())) {
                            gorunenler.add(ch);
                        }
                    }
                }
                tumKanallar.put(key, gorunenler);
                System.out.println("[SIRA-LOG] YÜKLEME kanal [" + turKodu + "/" + kategoriAdi + "] gizli="
                        + (hepsi != null ? hepsi.size() - gorunenler.size() : 0)
                        + "\n   DB SIRASI (*=gizli): " + kanalLogu(hepsi != null ? hepsi : new ArrayList<Channel>(), gizliUrller));
                for (Channel ch : gorunenler) {
                    if (!kanalPozSnapshot.containsKey(ch.getId())) {
                        kanalPozSnapshot.put(ch.getId(), ch.getPosition());
                    }
                }
            } catch (Exception e) {
                tumKanallar.put(key, new ArrayList<>());
            }
            if (tamamCallback != null) mainHandler.post(tamamCallback);
        }).start();
    }
    private static String diziAdiniCikar(String kanalAdi) {
        if (kanalAdi == null) return "";
        String temiz = kanalAdi.trim();
        java.util.regex.Matcher m1 = java.util.regex.Pattern
                .compile("(?i)[\\s\\-]+[Ss]\\d+\\s*[Ee]\\d+.*$")
                .matcher(temiz);
        if (m1.find()) {
            String ad = temiz.substring(0, m1.start()).trim();
            return ad.isEmpty() ? temiz : ad;
        }
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("(?i)[\\s\\-]+[Ss]ezon\\s*\\d+.*$")
                .matcher(temiz);
        if (m2.find()) {
            String ad = temiz.substring(0, m2.start()).trim();
            return ad.isEmpty() ? temiz : ad;
        }
        return temiz;
    }
    private void flatListeyiSifirla() {
        flatListe.clear();
        for (int i = 0; i < TUR_KODLARI.length; i++) {
            SiralamaItem tur = SiralamaItem.turOlustur(TUR_ETIKETLER[i], TUR_KODLARI[i]);
            tur.setAcik(false);
            flatListe.add(tur);
        }
    }
    private void acKapat(int adapterPos) {
        List<SiralamaItem> gor = adapter.getListe();
        if (adapterPos < 0 || adapterPos >= gor.size()) return;
        SiralamaItem item = gor.get(adapterPos);
        switch (item.getTip()) {
            case SiralamaItem.TIP_TUR:
                turAcKapat(item, adapterPos);
                break;
            case SiralamaItem.TIP_KATEGORI:
                kategoriAcKapat(item, adapterPos);
                break;
        }
    }
    private void turAcKapat(SiralamaItem turItem, int adapterPos) {
        if (turItem.isAcik()) {
            adapter.secimleriTemizle();
            String turKodu = turItem.getTur();
            List<SiralamaItem> kaldirilacak = new ArrayList<>();
            for (SiralamaItem item : flatListe) {
                if (item != turItem
                        && (item.getTip() == SiralamaItem.TIP_KATEGORI
                        || item.getTip() == SiralamaItem.TIP_KANAL)
                        && turKodu.equals(item.getTur())) {
                    kaldirilacak.add(item);
                }
            }
            flatListe.removeAll(kaldirilacak);
            turItem.setAcik(false);
            syncGorunenlerFlatten();
        } else {
            if (!veriYuklendi) {
                Toast.makeText(activity, "Veriler yükleniyor...", Toast.LENGTH_SHORT).show();
                return;
            }
            List<KategoriGirisi> katListesi = tumKategoriler.get(turItem.getTur());
            if (katListesi == null || katListesi.isEmpty()) {
                Toast.makeText(activity, "Bu türde kategori bulunamadı.", Toast.LENGTH_SHORT).show();
                return;
            }
            int turFlatPos = flatListe.indexOf(turItem);
            if (turFlatPos == -1) return;
            List<SiralamaItem> yeniKatOgeleri = new ArrayList<>();
            for (KategoriGirisi kg : katListesi) {
                if (FavorilerYoneticisi.FAVORI_KATEGORI_ADI.equals(kg.ad)) continue;
                SiralamaItem kat = SiralamaItem.kategoriOlustur(kg.ad, turItem.getTur(), kg.m3uAdi);
                yeniKatOgeleri.add(kat);
            }
            SiralamaItem favoriKat = SiralamaItem.kategoriOlustur(
                    FavorilerYoneticisi.FAVORI_KATEGORI_ADI, turItem.getTur(), "");
            yeniKatOgeleri.add(0, favoriKat);
            flatListe.addAll(turFlatPos + 1, yeniKatOgeleri);
            turItem.setAcik(true);
            syncGorunenlerFlatten();
        }
    }
    private void kategoriAcKapat(SiralamaItem katItem, int adapterPos) {
        boolean isSeries = "SERIES".equals(katItem.getTur());
        if (katItem.isAcik()) {
            adapter.secimleriTemizle();
            List<SiralamaItem> kaldirilacak = new ArrayList<>();
            for (SiralamaItem item : flatListe) {
                boolean kanal = item.getTip() == SiralamaItem.TIP_KANAL;
                boolean dizi  = item.getTip() == SiralamaItem.TIP_DIZI;
                if ((kanal || dizi)
                        && katItem.getTur().equals(item.getTur())
                        && katItem.getAd().equals(item.getKategoriAdi())) {
                    kaldirilacak.add(item);
                }
            }
            flatListe.removeAll(kaldirilacak);
            katItem.setAcik(false);
            syncGorunenlerFlatten();
        } else {
            String key = katItem.getTur() + "|" + katItem.getAd();
            if (!tumKanallar.containsKey(key)) {
                Toast.makeText(activity, "Kanallar yükleniyor...", Toast.LENGTH_SHORT).show();
                kanallarıYukle(katItem.getTur(), katItem.getAd(), () -> {
                    int yeniPos = adapter.getListe().indexOf(katItem);
                    if (yeniPos != -1) kategoriAcKapat(katItem, yeniPos);
                });
                return;
            }
            List<Channel> ogeler = tumKanallar.get(key);
            int katFlatPos = flatListe.indexOf(katItem);
            if (katFlatPos == -1) return;
            List<SiralamaItem> yeniOgeler = new ArrayList<>();
            boolean favoriSeriesKategorisi = isSeries
                    && FavorilerYoneticisi.FAVORI_KATEGORI_ADI.equals(katItem.getAd());
            if (!favoriSeriesKategorisi && (ogeler == null || ogeler.isEmpty())) {
                Toast.makeText(activity, "Bu kategoride içerik bulunamadı.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (favoriSeriesKategorisi) {
                List<String> diziAdlari = favoriDiziAdlari.get(katItem.getTur());
                if (diziAdlari != null) {
                    for (String diziAdi : diziAdlari) {
                        SiralamaItem diziItem = SiralamaItem.diziOlustur(
                                diziAdi, katItem.getTur(), katItem.getAd(), 0);
                        yeniOgeler.add(diziItem);
                    }
                }
            } else if (isSeries) {
                LinkedHashSet<String> gorulmus = new LinkedHashSet<>();
                for (Channel ch : ogeler) {
                    String diziAdi = diziAdiniCikar(ch.getName());
                    if (gorulmus.add(diziAdi)) {
                        SiralamaItem diziItem = SiralamaItem.diziOlustur(
                                diziAdi, katItem.getTur(),
                                katItem.getAd(), 0);
                        yeniOgeler.add(diziItem);
                    }
                }
            } else {
                for (Channel ch : ogeler) {
                    SiralamaItem kanalItem = SiralamaItem.kanalOlustur(
                            ch.getName(), ch.getUrl(), ch.getId(),
                            ch.getPosition(), katItem.getAd(), katItem.getTur());
                    yeniOgeler.add(kanalItem);
                }
            }
            flatListe.addAll(katFlatPos + 1, yeniOgeler);
            katItem.setAcik(true);
            syncGorunenlerFlatten();
        }
    }
    private void tasi(int hedefAdapterPos) {
        degisiklikVar = true;
        List<SiralamaItem> gor = adapter.getListe();
        if (hedefAdapterPos < 0 || hedefAdapterPos >= gor.size()) return;
        SiralamaItem hedef = gor.get(hedefAdapterPos);
        if (hedef.isSecili()) {
            Toast.makeText(activity, "Hedef öğe seçili olamaz.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (hedef.getTip() == SiralamaItem.TIP_KATEGORI
                && FavorilerYoneticisi.FAVORI_KATEGORI_ADI.equals(hedef.getAd())) {
            Toast.makeText(activity, "Favoriler kategorisi sabit konumdadır.", Toast.LENGTH_SHORT).show();
            return;
        }
        List<SiralamaItem> secililer = new ArrayList<>();
        for (SiralamaItem item : gor) {
            if (item.isSecili() && item.getTip() == hedef.getTip()) {
                secililer.add(item);
            }
        }
        if (secililer.isEmpty()) return;
        flatListe.removeAll(secililer);
        int yeniHedefPos = flatListe.indexOf(hedef);
        if (yeniHedefPos == -1) {
            flatListe.addAll(secililer);
            syncGorunenlerFlatten();
            return;
        }
        flatListe.addAll(yeniHedefPos, secililer);
        for (SiralamaItem item : secililer) item.setSecili(false);
        adapter.secimleriTemizle();
        adapter.notifyDataSetChanged();
        final int hedefFocusPos = hedefAdapterPos;
        recyclerView.postDelayed(() -> {
            RecyclerView.ViewHolder vh =
                    recyclerView.findViewHolderForAdapterPosition(hedefFocusPos);
            if (vh != null) vh.itemView.requestFocus();
        }, 100);
        syncGorunenlerFlatten();
        boolean favoriKategorisi = (hedef.getTip() == SiralamaItem.TIP_KANAL
                || hedef.getTip() == SiralamaItem.TIP_DIZI)
                && FavorilerYoneticisi.FAVORI_KATEGORI_ADI.equals(hedef.getKategoriAdi());
        if (favoriKategorisi) {
            favoriSirasiKaydet(hedef.getTur());
            String cacheKey = hedef.getTur() + "|" + FavorilerYoneticisi.FAVORI_KATEGORI_ADI;
            List<Channel> eskiKanallar = tumKanallar.get(cacheKey);
            if (eskiKanallar != null) {
                List<Channel> yeniSiraCache = new ArrayList<>();
                for (SiralamaItem item : flatListe) {
                    if ((item.getTip() == SiralamaItem.TIP_KANAL || item.getTip() == SiralamaItem.TIP_DIZI)
                            && hedef.getTur().equals(item.getTur())
                            && FavorilerYoneticisi.FAVORI_KATEGORI_ADI.equals(item.getKategoriAdi())) {
                        for (Channel ch : eskiKanallar) {
                            if (ch.getUrl() != null && ch.getUrl().equals(item.getUrl())) {
                                yeniSiraCache.add(ch);
                                break;
                            }
                        }
                    }
                }
                tumKanallar.put(cacheKey, yeniSiraCache);
                System.out.println("🔄 Favori tumKanallar cache güncellendi: " + yeniSiraCache.size() + " kanal");
            }
        } else {
            dbKaydet(hedef.getTip(), hedef.getTur(),
                    hedef.getTip() == SiralamaItem.TIP_KATEGORI ? null : hedef.getKategoriAdi());
        }
        Toast.makeText(activity,
                secililer.size() + " öğe taşındı.", Toast.LENGTH_SHORT).show();
    }
    private void dbKaydet(int tip, String turKodu, String kategoriAdi) {
        if (tip == SiralamaItem.TIP_KATEGORI) {
            kategoriSirasiKaydet(turKodu);
            List<KategoriGirisi> yeniKatListesi = new ArrayList<>();
            for (SiralamaItem item : flatListe) {
                if (item.getTip() == SiralamaItem.TIP_KATEGORI && turKodu.equals(item.getTur())) {
                    yeniKatListesi.add(new KategoriGirisi(item.getAd(), item.getM3uAdi()));
                }
            }
            tumKategoriler.put(turKodu, yeniKatListesi);
            List<KategoriItem> kategoriItems = new ArrayList<>();
            for (KategoriGirisi kg : yeniKatListesi) {
                kategoriItems.add(new KategoriItem(kg.ad, kg.m3uAdi));
            }
            com.example.livetvapp.database.KategoriCache.getInstance().put(turKodu, kategoriItems);
            if (kategoriGuncelleCallback != null) {
                final String finalTurKodu = turKodu;
                final List<KategoriItem> finalSirali = new ArrayList<>(kategoriItems);
                System.out.println("[SIRA-LOG] KATEGORİ-BİLDİRİM [" + turKodu + "] kaynakla: " + kategoriItemLogu(finalSirali));
                mainHandler.post(() ->
                        kategoriGuncelleCallback.onKategoriSirasiDegisti(finalTurKodu, finalSirali));
            }
            System.out.println("✅ KategoriCache PUT [" + turKodu + "]: " + yeniKatListesi.size() + " kategori");
        } else if (tip == SiralamaItem.TIP_DIZI && kategoriAdi != null) {
            final String finalTur = turKodu;
            final String finalKat = kategoriAdi;
            new Thread(() -> {
                try {
                    AppDatabase db = AppDatabase.getInstance(activity);
                    String key = finalTur + "|" + finalKat;
                    List<Channel> gercekKanallar = tumKanallar.get(key);
                    if (gercekKanallar == null) return;
                    List<Long> yeniSiraIdleri = new ArrayList<>();
                    for (SiralamaItem item : flatListe) {
                        if (item.getTip() == SiralamaItem.TIP_DIZI
                                && finalTur.equals(item.getTur())
                                && finalKat.equals(item.getKategoriAdi())) {
                            String diziAdi = item.getAd();
                            for (Channel ch : gercekKanallar) {
                                if (diziAdiniCikar(ch.getName()).equals(diziAdi)) {
                                    yeniSiraIdleri.add(ch.getId());
                                }
                            }
                        }
                    }
                    pozisyonlariYaz(db, finalTur, finalKat, yeniSiraIdleri);
                    System.out.println("✅ Dizi sırası DB'ye kaydedildi: " + finalKat);
                    kanalSirasiIsaretle(finalTur, finalKat);
                } catch (Exception e) {
                    System.out.println("❌ Dizi pozisyon kayıt hatası: " + e.getMessage());
                }
            }).start();
        } else if (tip == SiralamaItem.TIP_KANAL && kategoriAdi != null) {
            new Thread(() -> {
                try {
                    AppDatabase db = AppDatabase.getInstance(activity);
                    List<Long> yeniSiraIdleri = new ArrayList<>();
                    for (SiralamaItem item : flatListe) {
                        if (item.getTip() == SiralamaItem.TIP_KANAL
                                && turKodu.equals(item.getTur())
                                && kategoriAdi.equals(item.getKategoriAdi())) {
                            yeniSiraIdleri.add(item.getKanalId());
                        }
                    }
                    Map<Long, Integer> yeniPoz = pozisyonlariYaz(db, turKodu, kategoriAdi, yeniSiraIdleri);
                    for (SiralamaItem item : flatListe) {
                        if (item.getTip() == SiralamaItem.TIP_KANAL
                                && turKodu.equals(item.getTur())
                                && kategoriAdi.equals(item.getKategoriAdi())) {
                            Integer p = yeniPoz.get(item.getKanalId());
                            if (p != null) item.setPozisyon(p);
                        }
                    }
                    String key = turKodu + "|" + kategoriAdi;
                    List<Channel> kanallar = tumKanallar.get(key);
                    if (kanallar != null) {
                        List<Channel> yeniSira = new ArrayList<>();
                        for (SiralamaItem item : flatListe) {
                            if (item.getTip() == SiralamaItem.TIP_KANAL
                                    && turKodu.equals(item.getTur())
                                    && kategoriAdi.equals(item.getKategoriAdi())) {
                                for (Channel ch : kanallar) {
                                    if (ch.getId() == item.getKanalId()) {
                                        yeniSira.add(ch);
                                        break;
                                    }
                                }
                            }
                        }
                        tumKanallar.put(key, yeniSira);
                    }
                    System.out.println("✅ Kanal pozisyonları DB'ye kaydedildi: " + kategoriAdi);
                    kanalSirasiIsaretle(turKodu, kategoriAdi);
                } catch (Exception e) {
                    System.out.println("❌ Kanal pozisyon kayıt hatası: " + e.getMessage());
                }
            }).start();
        }
    }
    private void kanalSiraNumaralariniGuncelle() {
        Map<String, Integer> siralar = new LinkedHashMap<>();

        for (SiralamaItem item : flatListe) {
            if (item.getTip() != SiralamaItem.TIP_KANAL) continue;

            String tur = item.getTur() != null ? item.getTur() : "";
            String kategori = item.getKategoriAdi() != null ? item.getKategoriAdi() : "";
            String key = tur + "|" + kategori;

            int sira = siralar.containsKey(key) ? siralar.get(key) : 0;
            item.setSiraNo(sira + 1);
            siralar.put(key, sira + 1);
        }
    }

    private void syncGorunenlerFlatten() {
        View focused = recyclerView.getFocusedChild();
        int focusPos = focused != null
                ? recyclerView.getChildAdapterPosition(focused) : -1;

        kanalSiraNumaralariniGuncelle();
        adapter.setTumListe(new ArrayList<>(flatListe));
        if (focusPos != -1) {
            recyclerView.postDelayed(() -> {
                RecyclerView.ViewHolder vh =
                        recyclerView.findViewHolderForAdapterPosition(focusPos);
                if (vh != null) vh.itemView.requestFocus();
            }, 100);
        }
    }
    private String kategoriItemLogu(List<KategoriItem> liste) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < liste.size(); i++) {
            KategoriItem k = liste.get(i);
            if (i > 0) sb.append(" | ");
            sb.append(i).append(':').append(k.getKategoriAdi())
                    .append(" [").append(k.getKaynakAdi().isEmpty() ? "-" : k.getKaynakAdi()).append(']');
        }
        return sb.toString();
    }
    private String katLogu(List<String> liste, Set<String> gizliKat) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String key : liste) {
            String[] p = key.split(KAYIT_AYRAC);
            String ad = p.length == 2 ? p[1] : key;
            boolean gizli = p.length == 2 && gizliKat != null && gizliKat.contains(p[0] + "|" + p[1]);
            if (i > 0) sb.append(" | ");
            sb.append(i).append(':').append(gizli ? "*" : "").append(ad);
            i++;
        }
        return sb.toString();
    }
    private String kanalLogu(List<Channel> liste, Set<String> gizliUrller) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(liste.size(), 120);
        for (int i = 0; i < n; i++) {
            Channel ch = liste.get(i);
            boolean gizli = ch.getUrl() != null && gizliUrller != null && gizliUrller.contains(ch.getUrl());
            if (i > 0) sb.append(" | ");
            sb.append(i).append(':').append(gizli ? "*" : "").append(ch.getName());
        }
        if (liste.size() > n) sb.append(" | ...(+").append(liste.size() - n).append(")");
        return sb.toString();
    }
    private List<String> gizliKategorileriYerlestir(List<String> gorunen, List<String> eskiTam,
                                                    Set<String> gizliKat) {
        List<String> sonuc = new ArrayList<>(gorunen);
        if (eskiTam == null || gizliKat == null || gizliKat.isEmpty()) return sonuc;
        int konum = 0;
        for (String key : eskiTam) {
            String[] p = key.split(KAYIT_AYRAC);
            boolean gizli = p.length == 2 && gizliKat.contains(p[0] + "|" + p[1]);
            if (gizli) {
                if (!sonuc.contains(key)) {
                    sonuc.add(Math.min(konum, sonuc.size()), key);
                    konum++;
                }
            } else {
                int i = sonuc.indexOf(key);
                if (i >= 0) konum = i + 1;
            }
        }
        return sonuc;
    }
    private boolean kanalGizliMi(Channel ch, Set<String> gizliUrller) {
        return ch.getUrl() != null && gizliUrller != null && gizliUrller.contains(ch.getUrl());
    }
    private String oncekiGorunenAdi(List<Channel> liste, int idx, Set<String> gizliUrller) {
        for (int i = idx - 1; i >= 0; i--) {
            if (!kanalGizliMi(liste.get(i), gizliUrller)) return liste.get(i).getName();
        }
        return "(başta)";
    }
    private Map<Long, Integer> pozisyonlariYaz(AppDatabase db, String turKodu, String kategoriAdi,
                                               List<Long> yeniGorunenSira) {
        Map<Long, Integer> yeniPoz = new LinkedHashMap<>();
        List<Channel> hepsi = new ArrayList<>(
                db.channelDao().getChannelsByTypeAndCategoryAllM3U(turKodu, kategoriAdi));
        java.util.Collections.sort(hepsi, (a, b) -> a.getPosition() != b.getPosition()
                ? Integer.compare(a.getPosition(), b.getPosition())
                : Long.compare(a.getId(), b.getId()));
        Set<String> gizliUrller = GizlePaneli.getGizliKanalUrlleri(activity);
        Map<Long, Channel> gorunenler = new LinkedHashMap<>();
        for (Channel ch : hepsi) {
            if (!kanalGizliMi(ch, gizliUrller)) gorunenler.put(ch.getId(), ch);
        }
        int gizliSayisi = hepsi.size() - gorunenler.size();
        // 1) görünen kanallar yeni sırayla
        List<Channel> sonuc = new ArrayList<>();
        for (Long id : yeniGorunenSira) {
            Channel ch = gorunenler.remove(id);
            if (ch != null) sonuc.add(ch);
        }
        sonuc.addAll(gorunenler.values());
        // 2) gizli kanallar, eskiden hemen arkasında oldukları görünen kanalın arkasına
        int konum = 0;
        for (Channel ch : hepsi) {
            if (kanalGizliMi(ch, gizliUrller)) {
                sonuc.add(Math.min(konum, sonuc.size()), ch);
                konum++;
            } else {
                for (int i = 0; i < sonuc.size(); i++) {
                    if (sonuc.get(i).getId() == ch.getId()) { konum = i + 1; break; }
                }
            }
        }
        // 3) doğrulama logu
        StringBuilder komsu = new StringBuilder();
        for (int i = 0; i < hepsi.size(); i++) {
            Channel ch = hepsi.get(i);
            if (!kanalGizliMi(ch, gizliUrller)) continue;
            int j = sonuc.indexOf(ch);
            komsu.append("\n   gizli [").append(ch.getName()).append("]  önceki görünen: ")
                    .append(oncekiGorunenAdi(hepsi, i, gizliUrller)).append(" → ")
                    .append(oncekiGorunenAdi(sonuc, j, gizliUrller));
        }
        System.out.println("[SIRA-LOG] KAYIT kanal [" + turKodu + "/" + kategoriAdi + "] (*=gizli) toplam="
                + hepsi.size() + " gizli=" + gizliSayisi
                + "\n   ÖNCE : " + kanalLogu(hepsi, gizliUrller)
                + "\n   SONRA: " + kanalLogu(sonuc, gizliUrller)
                + komsu);
        // 4) yaz
        int yazilan = 0;
        for (int i = 0; i < sonuc.size(); i++) {
            Channel ch = sonuc.get(i);
            yeniPoz.put(ch.getId(), i);
            if (ch.getPosition() != i) {
                if (!kanalPozSnapshot.containsKey(ch.getId())) {
                    kanalPozSnapshot.put(ch.getId(), ch.getPosition());
                }
                db.channelDao().updateChannelPosition(ch.getId(), i);
                yazilan++;
            }
        }
        System.out.println("[SIRA-LOG]   DB'ye yazılan kanal sayısı=" + yazilan + " / " + sonuc.size());
        return yeniPoz;
    }
    private void kategoriSirasiKaydet(String turKodu) {
        degisiklikVar = true;
        try {
            android.content.SharedPreferences prefs =
                    activity.getSharedPreferences(PREFS_SIRALAMA, Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor editor = prefs.edit();
            List<String> gorunenSira = new ArrayList<>();
            for (SiralamaItem item : flatListe) {
                if (item.getTip() == SiralamaItem.TIP_KATEGORI && turKodu.equals(item.getTur())) {
                    gorunenSira.add(item.getM3uAdi() + KAYIT_AYRAC + item.getAd());
                }
            }
            List<String> kayitListesi = gizliKategorileriYerlestir(
                    gorunenSira, tamKategoriSirasi.get(turKodu),
                    GizlePaneli.getGizliKategoriler(activity));
            Set<String> gizliKatLog = GizlePaneli.getGizliKategoriler(activity);
            System.out.println("[SIRA-LOG] KAYIT kategori [" + turKodu + "] (*=gizli)"
                    + "\n   ESKİ TAM : " + katLogu(tamKategoriSirasi.get(turKodu) != null
                    ? tamKategoriSirasi.get(turKodu) : new ArrayList<String>(), gizliKatLog)
                    + "\n   GÖRÜNEN  : " + katLogu(gorunenSira, gizliKatLog)
                    + "\n   YENİ TAM : " + katLogu(kayitListesi, gizliKatLog));
            tamKategoriSirasi.put(turKodu, new ArrayList<>(kayitListesi));
            editor.putString(turKodu + "_sirasi", android.text.TextUtils.join(KAYIT_AYRAC, kayitListesi));
            editor.apply();
            System.out.println("✅ Kategori sırası kaydedildi [" + turKodu + "]: " + kayitListesi.size());
            veriYuklendi = false;
        } catch (Exception e) {
            System.out.println("❌ Kategori sırası kayıt hatası: " + e.getMessage());
        }
    }
    private void favoriSirasiKaydet(String turKodu) {
        degisiklikVar = true;
        FavorilerYoneticisi fy = FavorilerYoneticisi.getInstance(activity);
        List<String> yeniSira = new ArrayList<>();
        for (SiralamaItem item : flatListe) {
            if ((item.getTip() == SiralamaItem.TIP_KANAL || item.getTip() == SiralamaItem.TIP_DIZI)
                    && turKodu.equals(item.getTur())
                    && FavorilerYoneticisi.FAVORI_KATEGORI_ADI.equals(item.getKategoriAdi())) {
                String anahtar = item.getTip() == SiralamaItem.TIP_DIZI
                        ? item.getAd()
                        : item.getUrl();
                if (anahtar != null && !anahtar.isEmpty()) {
                    yeniSira.add(anahtar);
                }
            }
        }
        fy.sirayiGuncelle(turKodu, yeniSira);
        if ("SERIES".equals(turKodu)) {
            favoriDiziAdlari.put(turKodu, new ArrayList<>(yeniSira));
        }
        System.out.println("🔀 favoriSirasiKaydet [" + turKodu + "]: " + yeniSira.size() + " öğe");
        kanalSirasiIsaretle(turKodu, FavorilerYoneticisi.FAVORI_KATEGORI_ADI);
        veriYuklendi = false;
    }
    private List<String> kaydedilenSirayiYukle(String turKodu) {
        try {
            android.content.SharedPreferences prefs =
                    activity.getSharedPreferences(PREFS_SIRALAMA, Context.MODE_PRIVATE);
            String kayit = prefs.getString(turKodu + "_sirasi", null);
            if (kayit == null || kayit.isEmpty()) return null;
            String[] parcalar = kayit.split(KAYIT_AYRAC);
            List<String> liste = new ArrayList<>();

            for (int i = 0; i + 1 < parcalar.length; i += 2) {
                String cift = parcalar[i] + KAYIT_AYRAC + parcalar[i+1];
                liste.add(cift);
            }
            return liste;
        } catch (Exception e) {
            return null;
        }
    }
    private static class KategoriGirisi {
        String ad;
        String m3uAdi;
        KategoriGirisi(String ad, String m3uAdi) { this.ad = ad; this.m3uAdi = m3uAdi; }
    }
    public void goster() {
        if (panelView != null) panelView.setVisibility(View.VISIBLE);
    }
    public void gizle() {
        adapter.secimleriTemizle();
        if (panelView != null) {
            panelView.setVisibility(View.GONE);
            panelView.clearFocus();
        }
    }
    public boolean isVisible() {
        return panelView != null && panelView.getVisibility() == View.VISIBLE;
    }
    public boolean handleBack() {
        System.out.println("🔙 SiralamaPaneli.handleBack çağrıldı");
        System.out.println("🔙 SiralamaPaneli visible=" + isVisible());
        kaydetOnayiGoster();
        return true;
    }
    private void kaydetOnayiGoster() {
        if (!degisiklikVar) {
            kapat();
            return;
        }
        if (dialogAcik) return;
        dialogAcik = true;
        mainHandler.postDelayed(() ->
                        new androidx.appcompat.app.AlertDialog.Builder(activity)
                                .setMessage("Yaptığınız değişiklikleri kaydetmek istiyor musunuz?")
                                .setPositiveButton("Evet", (dialog, which) -> { dialogAcik = false; kapat(); })
                                .setNegativeButton("Hayır", (dialog, which) -> {
                                    dialogAcik = false;
                                    snapshotGeriAl();
                                    kapat();
                                })
                                .setCancelable(false)
                                .show()
                , 300);
    }
    private void snapshotGeriAl() {
        android.content.SharedPreferences.Editor ed =
                activity.getSharedPreferences(PREFS_SIRALAMA, Context.MODE_PRIVATE).edit();
        for (Map.Entry<String, String> e : siralamaSnapshot.entrySet()) {
            if (e.getValue() == null) ed.remove(e.getKey());
            else ed.putString(e.getKey(), e.getValue());
        }
        ed.apply();
        if (!kanalPozSnapshot.isEmpty()) {
            Map<Long, Integer> kopya = new LinkedHashMap<>(kanalPozSnapshot);
            geriAlmaSuruyor = true;
            new Thread(() -> {
                try {
                    AppDatabase db = AppDatabase.getInstance(activity);
                    for (Map.Entry<Long, Integer> e : kopya.entrySet()) {
                        db.channelDao().updateChannelPosition(e.getKey(), e.getValue());
                    }
                    System.out.println("↩️ Kanal pozisyonları geri alındı: " + kopya.size());
                } catch (Exception ex) {
                    System.out.println("❌ snapshotGeriAl kanal hatası: " + ex.getMessage());
                }
                // Geri yazma bitti: ana listeye şimdi haber ver (DB artık eski sırada)
                mainHandler.post(() -> {
                    geriAlmaSuruyor = false;
                    kanalSirasiBildir("geri alma bitti");
                });
            }).start();
        }
        com.example.livetvapp.database.KategoriCache.getInstance().invalidateAll();
        if (kategoriGuncelleCallback != null) {
            for (String turKodu : TUR_KODLARI) {
                String snapVal = siralamaSnapshot.get(turKodu + "_sirasi");
                if (snapVal == null || snapVal.isEmpty()) continue;
                List<KategoriItem> eskiSira = new ArrayList<>();
                String[] parts = snapVal.split(KAYIT_AYRAC);
                // kayıt formatı: m3uAdi###kategori###m3uAdi###kategori ...
                for (int i = 1; i < parts.length; i += 2) {
                    if (!parts[i].isEmpty()) eskiSira.add(new KategoriItem(parts[i], parts[i - 1]));
                }
                if (!eskiSira.isEmpty()) {
                    final String tk = turKodu;
                    final List<KategoriItem> es = new ArrayList<>(eskiSira);
                    System.out.println("[SIRA-LOG] KATEGORİ-BİLDİRİM geri alma [" + tk + "] kaynakla: " + kategoriItemLogu(es));
                    mainHandler.post(() -> kategoriGuncelleCallback.onKategoriSirasiDegisti(tk, es));
                }
            }
        }
        System.out.println("↩️ SiralamaPaneli değişiklikler iptal edildi");
    }
    public View getPanelView()            { return panelView; }
    public RecyclerView getRecyclerView() { return recyclerView; }
    public void veriGecersizKil() {
        veriYuklendi = false;
        tumKategoriler.clear();
        tamKategoriSirasi.clear();
        tumKanallar.clear();
        favoriDiziAdlari.clear();
    }
}