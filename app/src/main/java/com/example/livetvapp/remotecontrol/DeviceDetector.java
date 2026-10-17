package com.example.livetvapp.remotecontrol;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;

public class DeviceDetector {

    
    public static boolean isPhone(Context context) {
        return !isTablet(context) && !isTvBox(context);
    }

    
    public static boolean isTablet(Context context) {
        if (isTvBox(context)) return false;
        Configuration config = context.getResources().getConfiguration();
        int smallestWidth = config.smallestScreenWidthDp;
        return smallestWidth >= 600;
    }

    
    public static boolean isTvBox(Context context) {
        
        UiModeManager uiModeManager = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        if (uiModeManager != null &&
                uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true;
        }

        
        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            return true;
        }

        
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        String model = Build.MODEL.toLowerCase();
        String[] tvKeywords = {
                // "xiaomi" burada yoktu çünkü Xiaomi/Redmi/POCO telefonların üretici adı da "Xiaomi";
                // gerçek Xiaomi TV box ve çubukları zaten leanback/TV modu ile ya da "mibox"/"mitv"
                // model adı ile tanınır.
                "mibox", "mitv", "nvidia", "shield",
                "firetv", "amlogic", "rockchip", "h96",
                "x96", "tx9", "tx3", "mecool", "minix"
        };
        for (String keyword : tvKeywords) {
            if (manufacturer.contains(keyword) || model.contains(keyword)) {
                return true;
            }
        }

        return false;
    }

    
    public static String getDeviceTypeString(Context context) {
        if (isTvBox(context)) return "TV/TV-BOX";
        if (isTablet(context)) return "TABLET";
        return "TELEFON";
    }
}