package com.example.livetvapp.paneller;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.livetvapp.Adapter.AramaAdapter;
import com.example.livetvapp.Channel;
import com.example.livetvapp.R;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AramaPaneli {
    public interface DpadModuSaglayici { boolean isDpadModu(); }
    private DpadModuSaglayici dpadModuSupplier;
    public interface RemoteKontrolSaglayici { boolean isRemoteClientConnected(); }
    private RemoteKontrolSaglayici remoteKontrolSupplier;
    public void setRemoteKontrolSupplier(RemoteKontrolSaglayici s) { this.remoteKontrolSupplier = s; }
    public void setDpadModuSupplier(DpadModuSaglayici s) { this.dpadModuSupplier = s; }
    private Runnable onKapatCallback;
    public void setOnKapatCallback(Runnable r) { this.onKapatCallback = r; }
    public interface OnKanalSecimListener {
        void onKanalSecildi(Channel kanal);
    }
    public interface OnDiziSecimListener {
        void onDiziSecildi(String diziAdi, Channel temsilKanal);
    }
    public interface OnAramaIstekListener {
        void onAramaIstegi(String sorgu, String contentType, int offset);
    }
    private AppCompatActivity activity;
    private View panelView;
    private EditText etArama;
    private RecyclerView rvCanliTV, rvFilm, rvDizi;
    private TextView tvCanliBaslik, tvFilmBaslik, tvDiziBaslik;
    private TextView tvSonucYok;
    private LinearLayout grupCanli, grupFilm, grupDizi;
    private OzelKlavye klavye;
    private OnKanalSecimListener secimListener;
    private OnDiziSecimListener diziSecimListener;
    private OnAramaIstekListener aramaIstekCallback;
    private AramaAdapter adapterCanli, adapterFilm, adapterDizi;
    private int offsetCanli = 0, offsetFilm = 0, offsetDizi = 0;
    private boolean yukleniyor = false;
    private boolean kutuModu = false;   // dokunmatik: her tür kendi kutusunda kayar
    private boolean bittiCanli = false, bittiFilm = false, bittiDizi = false;
    private String mevcutSorgu = "";
    private final Map<String, Channel> diziTekilMap = new LinkedHashMap<>();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable aramaRunnable;
    private static final long ARAMA_GECIKME = 500;
    private int normalPanelYukseklik = 0;
    private boolean sonucFokusModuAktif = false;
    private android.view.ViewTreeObserver.OnGlobalLayoutListener klavyeDurumListener = null;

    public AramaPaneli(AppCompatActivity activity) {
        this.activity = activity;
    }

    public void setOnKanalSecimListener(OnKanalSecimListener l) { this.secimListener = l; }
    public void setOnDiziSecimListener(OnDiziSecimListener l)   { this.diziSecimListener = l; }
    public void setOnAramaIstekListener(OnAramaIstekListener l) { this.aramaIstekCallback = l; }

    public void olustur() {
        panelView = LayoutInflater.from(activity).inflate(R.layout.panel_arama, null);
        etArama       = panelView.findViewById(R.id.etArama);
        rvCanliTV     = panelView.findViewById(R.id.rvCanliTV);
        rvFilm        = panelView.findViewById(R.id.rvFilm);
        rvDizi        = panelView.findViewById(R.id.rvDizi);
        tvCanliBaslik = panelView.findViewById(R.id.tvCanliBaslik);
        tvFilmBaslik  = panelView.findViewById(R.id.tvFilmBaslik);
        tvDiziBaslik  = panelView.findViewById(R.id.tvDiziBaslik);
        grupCanli     = panelView.findViewById(R.id.grupCanli);
        grupFilm      = panelView.findViewById(R.id.grupFilm);
        grupDizi      = panelView.findViewById(R.id.grupDizi);
        tvSonucYok    = panelView.findViewById(R.id.tvSonucYok);

        rvCanliTV.setLayoutManager(new LinearLayoutManager(activity));
        rvFilm.setLayoutManager(new LinearLayoutManager(activity));
        rvDizi.setLayoutManager(new LinearLayoutManager(activity));

        rvKaydirmaBagla();

        etArama.setOnFocusChangeListener((v, hasFocus) -> {
            if (klavye != null && klavye.isVisible()) return;
            etArama.setBackgroundColor(hasFocus ? 0xFFFFDD00 : 0xAA220000);
            etArama.setTextColor(hasFocus ? 0xFF000000 : 0xFFFFFFFF);
            etArama.setHintTextColor(hasFocus ? 0xFF000000 : 0x88FFFFFF);
        });

        etArama.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                aramaYap(s.toString());
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        etArama.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                    || keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) {
                gizle();
                return true;
            }
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                return klavye.handleCursorKey(keyCode);
            }
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                if (etArama.getText().length() <= 1) return true;
                if (grupFilm.getVisibility() == View.VISIBLE) {
                    tvFilmBaslik.requestFocus();
                } else if (grupDizi.getVisibility() == View.VISIBLE) {
                    tvDiziBaslik.requestFocus();
                } else if (grupCanli.getVisibility() == View.VISIBLE) {
                    tvCanliBaslik.requestFocus();
                }
                return true;
            }
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                if (klavye.isVisible()) {
                    klavye.focusKlavyeye();
                } else {
                    klavye.goster(etArama);
                    klavye.focusKlavyeye();
                }
                return true;
            }
            return false;
        });

        klavye = new OzelKlavye(activity);
        klavye.ekranaEkle();
        klavye.setOnBackListener(this::gizle);
        klavye.setOnEnterListener(metin -> {
            if (grupCanli.getVisibility() == View.VISIBLE) {
                tvCanliBaslik.requestFocus();
            } else if (grupFilm.getVisibility() == View.VISIBLE) {
                tvFilmBaslik.requestFocus();
            } else if (grupDizi.getVisibility() == View.VISIBLE) {
                tvDiziBaslik.requestFocus();
            } else {
                etArama.requestFocus();
            }
        });
        klavye.setOnFocusPaneleGecListener(() -> etArama.requestFocus());
        panelView.setVisibility(View.GONE);
        klavye.setOnKapatListener(() -> etArama.requestFocus());

        panelView.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (newFocus == null || panelView.getVisibility() != View.VISIBLE) return;
            boolean yeniOdakSonucta = isDescendant(panelView, newFocus) && newFocus != etArama;
            boolean yeniOdakAramada = (newFocus == etArama);
            if (yeniOdakSonucta && !sonucFokusModuAktif) {
                if (etArama.getText().length() <= 1) {
                    etArama.requestFocus();
                    return;
                }
                sonucFokusModuAktif = true;
                klavye.gizle();
                panelTamEkranYap();
            } else if (yeniOdakAramada && sonucFokusModuAktif) {
                sonucFokusModuAktif = false;
                if (dpadModuSupplier != null && dpadModuSupplier.isDpadModu()) {
                    klavye.goster(etArama);
                }
                panelYuksekliginiSifirla();
            }
        });

        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(dm);
        int klavyeH = dm.heightPixels / 2;
        normalPanelYukseklik = dm.heightPixels - klavyeH;

        activity.addContentView(panelView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        normalPanelYukseklik));
    }

    // Her sonuç kutusu (Film / Dizi / Canlı TV) kendi içinde kayar.
    // Kutunun içinde başlayan sürtme sadece o kutuyu kaydırır, dış alana geçmez.
    private void rvKaydirmaBagla() {
        RecyclerView[] kutular = {rvFilm, rvDizi, rvCanliTV};
        for (RecyclerView rv : kutular) {
            rv.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
                @Override
                public boolean onInterceptTouchEvent(RecyclerView r, android.view.MotionEvent e) {
                    if (kutuModu && e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                            && (r.canScrollVertically(1) || r.canScrollVertically(-1))
                            && r.getParent() != null) {
                        r.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return false;
                }
            });
            rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(RecyclerView r, int dx, int dy) {
                    if (dy > 0) dahaYuklemeKontrol();
                }
            });
        }
    }

    // Dokunmatikte kutular sabit yükseklikte, TV'de eskisi gibi (içeriği kadar uzar).
    private void kutuModuAyarla(boolean dokunmatik) {
        kutuModu = dokunmatik;
        float yogunluk = activity.getResources().getDisplayMetrics().density;
        RecyclerView[] kutular = {rvFilm, rvDizi, rvCanliTV};
        for (RecyclerView rv : kutular) {
            android.view.ViewGroup.LayoutParams lp = rv.getLayoutParams();
            if (lp != null) {
                lp.height = dokunmatik ? (int) (220 * yogunluk)
                        : android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                rv.setLayoutParams(lp);
            }
            rv.setBackgroundColor(dokunmatik ? 0xFF0D2146 : 0x00000000);
            rv.setVerticalScrollBarEnabled(dokunmatik);
        }
        // Son kutunun altında boşluk: kutunun dışında kaydırmak için yer kalsın
        if (grupFilm != null && grupFilm.getParent() instanceof View) {
            View icerik = (View) grupFilm.getParent();
            icerik.setPadding(icerik.getPaddingLeft(), icerik.getPaddingTop(),
                    icerik.getPaddingRight(), dokunmatik ? (int) (120 * yogunluk) : 0);
        }
    }

    private boolean altinaYakin(RecyclerView rv, boolean bitti, Object adapter) {
        if (bitti || adapter == null || rv == null) return false;
        if (rv.getVisibility() != View.VISIBLE || rv.getHeight() == 0) return false;
        RecyclerView.LayoutManager lm = rv.getLayoutManager();
        if (!(lm instanceof LinearLayoutManager)) return false;
        int toplam = lm.getItemCount();
        if (toplam == 0) return false;
        return ((LinearLayoutManager) lm).findLastVisibleItemPosition() >= toplam - 5;
    }

    private void dahaYuklemeKontrol() {
        if (yukleniyor || mevcutSorgu.isEmpty() || !isVisible()) return;
        if (altinaYakin(rvFilm, bittiFilm, adapterFilm)) {
            System.out.println("📜 [ARAMA-SCROLL] Film sonraki sayfa | offset=" + offsetFilm);
            dahayukle("MOVIE");
        } else if (altinaYakin(rvDizi, bittiDizi, adapterDizi)) {
            System.out.println("📜 [ARAMA-SCROLL] Dizi sonraki sayfa | offset=" + offsetDizi);
            dahayukle("SERIES");
        } else if (altinaYakin(rvCanliTV, bittiCanli, adapterCanli)) {
            System.out.println("📜 [ARAMA-SCROLL] Canlı TV sonraki sayfa | offset=" + offsetCanli);
            dahayukle("LIVE");
        }
    }

    private void dahayukle(String contentType) {
        if (yukleniyor || aramaIstekCallback == null) return;
        int offset;
        switch (contentType) {
            case "LIVE":   offset = offsetCanli; break;
            case "MOVIE":  offset = offsetFilm;  break;
            case "SERIES": offset = offsetDizi;  break;
            default: return;
        }
        yukleniyor = true;
        aramaIstekCallback.onAramaIstegi(mevcutSorgu, contentType, offset);
    }

    public void goster() {

        panelView.setVisibility(View.VISIBLE);
        etArama.setText("");
        temizleSonuclar();
        sonucFokusModuAktif = false;
        panelYuksekliginiSifirla();

        kutuModuAyarla(!(dpadModuSupplier != null && dpadModuSupplier.isDpadModu()));

        boolean remoteKontrolBagliMi = remoteKontrolSupplier != null && remoteKontrolSupplier.isRemoteClientConnected();

        if (dpadModuSupplier != null && dpadModuSupplier.isDpadModu()) {
            klavyeDurumDinleyicisiniKaldir();
            etArama.post(() -> {
                etArama.requestFocus();
                if (!remoteKontrolBagliMi) {
                    klavye.goster(etArama);
                }
            });
        } else {
            klavye.gizle();
            etArama.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            etArama.post(() -> {
                etArama.requestFocus();
                etArama.postDelayed(() -> {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager)
                                    activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(etArama,
                            android.view.inputmethod.InputMethodManager.SHOW_FORCED);
                }, 200);
            });
            klavyeDurumDinleyicisiniBaslat();
        }
    }

    public void gizle() {
        klavye.gizle();
        klavyeDurumDinleyicisiniKaldir();
        panelYuksekliginiSifirla();
        panelView.setVisibility(View.GONE);
        if (aramaRunnable != null) handler.removeCallbacks(aramaRunnable);
        if (onKapatCallback != null) onKapatCallback.run();
    }

    public boolean isVisible() {
        return panelView != null && panelView.getVisibility() == View.VISIBLE;
    }

    public View getPanelView() { return panelView; }

    public void aramaYap(String sorgu) {
        if (aramaRunnable != null) handler.removeCallbacks(aramaRunnable);
        if (sorgu == null || sorgu.trim().length() < 2) {
            temizleSonuclar();
            return;
        }
        aramaRunnable = () -> {
            if (aramaIstekCallback == null) return;
            mevcutSorgu  = sorgu.trim();
            System.out.println("🔍 [ARAMA] aramaYap tetiklendi: " + mevcutSorgu);
            offsetCanli  = 0;
            offsetFilm   = 0;
            offsetDizi   = 0;
            adapterCanli = null;
            adapterFilm  = null;
            adapterDizi  = null;
            diziTekilMap.clear();
            bittiCanli = false;
            bittiFilm  = false;
            bittiDizi  = false;
            yukleniyor = false;
            temizleSonuclar();
            aramaIstekCallback.onAramaIstegi(mevcutSorgu, "LIVE",   0);
            aramaIstekCallback.onAramaIstegi(mevcutSorgu, "MOVIE",  0);
            aramaIstekCallback.onAramaIstegi(mevcutSorgu, "SERIES", 0);
        };
        handler.postDelayed(aramaRunnable, ARAMA_GECIKME);
    }

    public void sayfaEkle(String contentType, List<Channel> channels, java.util.Map<String, Integer> siraMap) {
        yukleniyor = false;
        System.out.println("📥 [ARAMA] sayfaEkle: tip=" + contentType + " adet=" + (channels != null ? channels.size() : 0));
        if (channels == null) return;

        switch (contentType) {
            case "LIVE":
                if (adapterCanli == null) {
                    grupBaslat(grupCanli, tvCanliBaslik, rvCanliTV, "Canlı TV", contentType);
                }
                if (!channels.isEmpty()) {
                    adapterCanli.ekleSiraBilgisi(siraMap);
                    adapterCanli.appendItems(channels);
                    offsetCanli += channels.size();

                    String okCanli = rvCanliTV.getVisibility() == View.VISIBLE ? " ▼" : " ▶";
                    tvCanliBaslik.setText("Canlı TV (" + offsetCanli + ")" + okCanli);
                } else {
                    bittiCanli = true;
                }
                break;
            case "MOVIE":
                if (adapterFilm == null) {
                    grupBaslat(grupFilm, tvFilmBaslik, rvFilm, "Film", contentType);
                }
                if (!channels.isEmpty()) {
                    adapterFilm.ekleSiraBilgisi(siraMap);
                    adapterFilm.appendItems(channels);
                    offsetFilm += channels.size();

                    String okFilm = rvFilm.getVisibility() == View.VISIBLE ? " ▼" : " ▶";
                    tvFilmBaslik.setText("Film (" + offsetFilm + ")" + okFilm);
                } else {
                    bittiFilm = true;
                }
                break;
            case "SERIES":
                List<Channel> tekilDiziler = tekilDiziListesiOlustur(channels);
                if (adapterDizi == null && !tekilDiziler.isEmpty()) {
                    grupBaslat(grupDizi, tvDiziBaslik, rvDizi, "Dizi", contentType);
                }
                if (!tekilDiziler.isEmpty() && adapterDizi != null) {
                    adapterDizi.ekleSiraBilgisi(siraMap);
                    adapterDizi.appendItems(tekilDiziler);
                }
                offsetDizi += channels.size();
                if (channels.isEmpty()) bittiDizi = true;

                if (adapterDizi != null) {
                    String okDizi = rvDizi.getVisibility() == View.VISIBLE ? " ▼" : " ▶";
                    tvDiziBaslik.setText("Dizi (" + adapterDizi.getItemCount() + ")" + okDizi);
                }
                break;
        }
        sonucYokKontrol();
        handler.postDelayed(this::dahaYuklemeKontrol, 120);
    }

    private List<Channel> tekilDiziListesiOlustur(List<Channel> kanallar) {
        List<Channel> sonuc = new ArrayList<>();
        for (Channel kanal : kanallar) {
            String diziAdi = diziAdiniCikar(kanal.getName());
            if (diziAdi == null || diziAdi.isEmpty()) diziAdi = kanal.getName();
            if (!diziTekilMap.containsKey(diziAdi)) {
                diziTekilMap.put(diziAdi, kanal);
                Channel temsilKanal = new Channel(diziAdi, kanal.getUrl(),
                        kanal.getCategory(), kanal.getLogo(), kanal.getSourceName());
                temsilKanal.setContentType("SERIES");
                sonuc.add(temsilKanal);
            }
        }
        return sonuc;
    }

    private String diziAdiniCikar(String kanalAdi) {
        if (kanalAdi == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(.*?)[\\s\\-]*[Ss]\\d{1,2}[\\s]*[Ee]\\d{1,3}.*",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(kanalAdi.trim());
        if (m.find()) {
            String ad = m.group(1).trim().replaceAll("[\\-_]+$", "").trim();
            if (!ad.isEmpty()) return karakter(ad);
        }
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("(.*?)[\\s\\-]*[Ss]ezon[\\s]*\\d+.*",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(kanalAdi.trim());
        if (m2.find()) {
            String ad = m2.group(1).trim().replaceAll("[\\-_]+$", "").trim();
            if (!ad.isEmpty()) return karakter(ad);
        }
        return karakter(kanalAdi.trim());
    }

    private String karakter(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void grupBaslat(LinearLayout grup, TextView baslik, RecyclerView rv,
                            String baslikMetin, String contentType) {


        baslik.setText(baslikMetin + " (0) ▶");
        baslik.setFocusable(true);
        baslik.setClickable(true);
        rv.setVisibility(View.GONE);


        baslik.setOnClickListener(v -> {
            String t = baslik.getText().toString();
            if (rv.getVisibility() == View.VISIBLE) {
                rv.setVisibility(View.GONE);
                baslik.setText(t.replace(" ▼", " ▶"));
            } else {
                rv.setVisibility(View.VISIBLE);
                baslik.setText(t.replace(" ▶", " ▼"));
                handler.postDelayed(this::dahaYuklemeKontrol, 120);
            }
        });

        baslik.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                    || keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) {
                etArama.requestFocus();
                return true;
            }

            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                String t = baslik.getText().toString();
                if (rv.getVisibility() == View.VISIBLE) {
                    rv.setVisibility(View.GONE);
                    baslik.setText(t.replace(" ▼", " ▶"));
                } else {
                    rv.setVisibility(View.VISIBLE);
                    baslik.setText(t.replace(" ▶", " ▼"));
                    handler.postDelayed(this::dahaYuklemeKontrol, 120);
                }
                return true;
            }
            return false;
        });

        baslik.setOnFocusChangeListener((v, hasFocus) ->
                baslik.setBackgroundColor(hasFocus ? 0xFFFF0000 : 0xAA440000));

        AramaAdapter adapter;
        if ("SERIES".equals(contentType)) {
            adapter = new AramaAdapter(new ArrayList<>(), kanal -> {
                if (diziSecimListener != null) {
                    diziSecimListener.onDiziSecildi(kanal.getName(), kanal);
                }
            });
        } else {
            adapter = new AramaAdapter(new ArrayList<>(), kanal -> {
                if (secimListener != null) secimListener.onKanalSecildi(kanal);
            });
        }
        adapter.setKapatListener(() -> etArama.requestFocus());
        rv.setAdapter(adapter);

        switch (contentType) {
            case "LIVE":   adapterCanli = adapter; break;
            case "MOVIE":  adapterFilm  = adapter; break;
            case "SERIES": adapterDizi  = adapter; break;
        }
    }

    private void sonucYokKontrol() {
        boolean hicSonucYok =
                (grupCanli.getVisibility() != View.VISIBLE) &&
                        (grupFilm.getVisibility()  != View.VISIBLE) &&
                        (grupDizi.getVisibility()  != View.VISIBLE);
        if (tvSonucYok != null) {
            tvSonucYok.setVisibility(hicSonucYok ? View.VISIBLE : View.GONE);
        }
    }


    private void temizleSonuclar() {
        if (tvCanliBaslik != null) tvCanliBaslik.setText("Canlı TV (0) ▶");
        if (tvFilmBaslik  != null) tvFilmBaslik.setText("Film (0) ▶");
        if (tvDiziBaslik  != null) tvDiziBaslik.setText("Dizi (0) ▶");
        if (rvCanliTV != null) { rvCanliTV.setAdapter(null); rvCanliTV.setVisibility(View.GONE); }
        if (rvFilm    != null) { rvFilm.setAdapter(null);    rvFilm.setVisibility(View.GONE); }
        if (rvDizi    != null) { rvDizi.setAdapter(null);    rvDizi.setVisibility(View.GONE); }
        if (tvSonucYok != null) tvSonucYok.setVisibility(View.GONE);
    }

    private void klavyeDurumDinleyicisiniBaslat() {
        klavyeDurumDinleyicisiniKaldir();
        final View rootView = activity.getWindow().getDecorView().getRootView();
        klavyeDurumListener = () -> {
            android.graphics.Rect r = new android.graphics.Rect();
            rootView.getWindowVisibleDisplayFrame(r);
            int ekranYuksekligi = rootView.getHeight();
            int klavyeYuksekligi = ekranYuksekligi - r.bottom;
            boolean klavyeAcik = klavyeYuksekligi > ekranYuksekligi * 0.15f;
            if (klavyeAcik) {
                panelYuksekliginiSifirla();
            } else {
                panelTamEkranYap();
            }
        };
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(klavyeDurumListener);
    }

    private void klavyeDurumDinleyicisiniKaldir() {
        if (klavyeDurumListener == null) return;
        final View rootView = activity.getWindow().getDecorView().getRootView();
        rootView.getViewTreeObserver().removeOnGlobalLayoutListener(klavyeDurumListener);
        klavyeDurumListener = null;
    }

    private void panelTamEkranYap() {
        if (panelView == null) return;
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(dm);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panelView.getLayoutParams();
        if (lp != null && lp.height != dm.heightPixels) {
            lp.height = dm.heightPixels;
            panelView.setLayoutParams(lp);
        }
    }

    private void panelYuksekliginiSifirla() {
        if (panelView == null || normalPanelYukseklik == 0) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panelView.getLayoutParams();
        if (lp != null && lp.height != normalPanelYukseklik) {
            lp.height = normalPanelYukseklik;
            panelView.setLayoutParams(lp);
        }
    }

    private boolean isDescendant(View parent, View child) {
        if (parent == null || child == null) return false;
        android.view.ViewParent current = child.getParent();
        while (current != null) {
            if (current == parent) return true;
            current = current.getParent();
        }
        return false;
    }
}