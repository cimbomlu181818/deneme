package com.example.livetvapp;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.util.Log;

import java.security.MessageDigest;

public class AzshApplication extends Application {

    // Gerçek imzamızın SHA-256 fingerprint'i (küçük harf, iki nokta üst üste olmadan).
    private static final String BEKLENEN_IMZA_SHA256 =
            "a9cdc6823f9f5149f493ccee5ed1c8390cd11170f705bd6d7c553f9f462b6c4b";

    @Override
    public void onCreate() {
        super.onCreate();

        // Hata günlüğü ve hata kodu penceresi (çökme yakalama dahil) — en başta kurulur.
        HataGunlugu.baslat(this);

        // Dönen değer null ise imza doğru. Değilse {hataKodu, detay}.
        String[] sonuc = imzaKontrol();
        if (sonuc != null) {
            Log.e("AzshApplication", "Imza kontrolu basarisiz: " + sonuc[0]);
            // Kullanıcıya hata kodu gösterilir; pencere kapatılınca
            // (ya da en geç 15 sn sonra) uygulama kapanır.
            HataGunlugu.imzaHatasi(sonuc[0], sonuc[1]);
        }
    }

    private String[] imzaKontrol() {
        try {
            PackageManager pm = getPackageManager();
            String paketAdi = getPackageName();
            StringBuilder bulunanlar = new StringBuilder();

            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo pInfo = pm.getPackageInfo(paketAdi, PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo signingInfo = pInfo.signingInfo;
                signatures = signingInfo.hasMultipleSigners()
                        ? signingInfo.getApkContentsSigners()
                        : signingInfo.getSigningCertificateHistory();
            } else {
                @SuppressWarnings("deprecation")
                PackageInfo pInfo = pm.getPackageInfo(paketAdi, PackageManager.GET_SIGNATURES);
                @SuppressWarnings("deprecation")
                Signature[] eski = pInfo.signatures;
                signatures = eski;
            }

            if (signatures == null || signatures.length == 0) {
                return new String[]{"E-IMZA-2", "Cihaz imza bilgisi döndürmedi (boş)."};
            }
            for (Signature sig : signatures) {
                String hash = sha256Hex(sig.toByteArray());
                if (BEKLENEN_IMZA_SHA256.equalsIgnoreCase(hash)) {
                    return null;
                }
                // Tam hash yerine yalnızca ilk 12 karakter gösterilir.
                bulunanlar.append(hash, 0, 12).append(' ');
            }
            return new String[]{"E-IMZA-1", "Bulunan imza: " + bulunanlar.toString().trim()};
        } catch (Exception e) {
            Log.e("AzshApplication", "Imza kontrolu sirasinda hata: " + e.getMessage());
            return new String[]{"E-IMZA-2", e.getClass().getSimpleName() + ": " + e.getMessage()};
        }
    }

    private String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}