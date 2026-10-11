import datetime
from django.contrib import admin
from django.shortcuts import render, redirect
from django.urls import path
from django.utils import timezone
from django.contrib import messages
from django.core.mail import EmailMessage, get_connection
from django.db.models import Q
from .models import Kullanici, Ayarlar, Cihaz


class CihazInline(admin.TabularInline):
    model = Cihaz
    extra = 0
    fields = ('marka', 'model', 'cihaz_id', 'eklenme_tarihi')
    readonly_fields = ('cihaz_id', 'eklenme_tarihi')


@admin.action(description="Trial süresini uzat")
def trial_uzat_action(modeladmin, request, queryset):
    secili_idler = list(queryset.values_list('id', flat=True))
    request.session['trial_uzat_idler'] = secili_idler
    return redirect('admin:trial_uzat_ekrani')


# Gmail ücretsiz hesabın günlük limiti ~500 mail; güvenli taraf için tek seferde en fazla bu kadar.
MAIL_TEK_SEFERDE_MAX = 400


@admin.action(description="Seçilenlere mail gönder")
def mail_gonder_action(modeladmin, request, queryset):
    request.session['mail_gonder_idler'] = list(queryset.values_list('id', flat=True))
    return redirect('admin:mail_gonder_ekrani')


@admin.action(description="E-postayı doğrula (seçilenleri onayla)")
def mail_dogrula_action(modeladmin, request, queryset):
    # Zaten doğrulanmış olanlara dokunmadan, sadece doğrulanmamışları onaylar.
    bekleyenler = queryset.filter(mail_dogrulandi=False)
    sayi = bekleyenler.update(
        mail_dogrulandi=True,
        dogrulama_kodu=None,
        dogrulama_kodu_zamani=None,
    )
    atlanan = queryset.count() - sayi
    mesaj = f"{sayi} kullanıcının e-postası doğrulandı."
    if atlanan:
        mesaj += f" ({atlanan} kullanıcı zaten doğrulanmıştı, değiştirilmedi.)"
    modeladmin.message_user(request, mesaj, messages.SUCCESS)


class SonBaglantiFiltre(admin.SimpleListFilter):
    title = "Son bağlantı"
    parameter_name = "son_baglanti"

    def lookups(self, request, model_admin):
        return [
            ('hic', 'Hiç bağlanmamış'),
            ('7', '7 gündür bağlanmayan'),
            ('15', '15 gündür bağlanmayan'),
            ('30', '30 gündür bağlanmayan'),
            ('60', '60 gündür bağlanmayan'),
            ('son1', 'Son 24 saatte bağlanan'),
        ]

    def queryset(self, request, queryset):
        v = self.value()
        simdi = timezone.now()
        if v == 'hic':
            return queryset.filter(son_gorulme__isnull=True)
        if v in ('7', '15', '30', '60'):
            sinir = simdi - datetime.timedelta(days=int(v))
            # Hiç bağlanmamış olanlar da "bağlanmayan" sayılır.
            return queryset.filter(Q(son_gorulme__lt=sinir) | Q(son_gorulme__isnull=True))
        if v == 'son1':
            return queryset.filter(son_gorulme__gte=simdi - datetime.timedelta(hours=24))
        return queryset


@admin.register(Kullanici)
class KullaniciAdmin(admin.ModelAdmin):
    list_display = ('email', 'olusturma_tarihi', 'son_gorulme', 'trial_bitis', 'cihaz_sayisi', 'mail_dogrulandi', 'premium_mi')
    search_fields = ('email',)
    list_filter = ('premium_mi', 'mail_dogrulandi', SonBaglantiFiltre)
    readonly_fields = ('son_gorulme',)
    inlines = [CihazInline]
    actions = [trial_uzat_action, mail_dogrula_action, mail_gonder_action]

    def cihaz_sayisi(self, obj):
        return obj.cihazlar.count()
    cihaz_sayisi.short_description = "Cihaz Sayısı"

    def get_urls(self):
        urls = super().get_urls()
        ozel_urls = [
            path('trial-uzat/', self.admin_site.admin_view(self.trial_uzat_ekrani), name='trial_uzat_ekrani'),
            path('mail-gonder/', self.admin_site.admin_view(self.mail_gonder_ekrani), name='mail_gonder_ekrani'),
        ]
        return ozel_urls + urls

    def trial_uzat_ekrani(self, request):
        idler = request.session.get('trial_uzat_idler', [])
        kullanicilar = Kullanici.objects.filter(id__in=idler)

        if request.method == 'POST':
            secenek = request.POST.get('secenek')
            secili_idler = request.POST.getlist('secili_id')
            hedef_kullanicilar = Kullanici.objects.filter(id__in=secili_idler)

            if secenek == 'bitir':
                hedef_kullanicilar.update(trial_bitis=timezone.now())
                messages.success(request, f"{hedef_kullanicilar.count()} kullanıcının trial süresi anında bitirildi.")
            elif secenek == 'ozel':
                ozel_tarih = request.POST.get('ozel_tarih')
                if ozel_tarih:
                    tarih = datetime.datetime.strptime(ozel_tarih, '%Y-%m-%d')
                    tarih = timezone.make_aware(
                        tarih.replace(hour=23, minute=59, second=59)
                    )
                    hedef_kullanicilar.update(trial_bitis=tarih)
                    messages.success(request, f"{hedef_kullanicilar.count()} kullanıcının trial süresi {ozel_tarih} tarihine kadar uzatıldı.")
            else:
                gun_sayisi = int(secenek)
                yeni_tarih = timezone.now() + datetime.timedelta(days=gun_sayisi)
                hedef_kullanicilar.update(trial_bitis=yeni_tarih)
                messages.success(request, f"{hedef_kullanicilar.count()} kullanıcının trial süresi {gun_sayisi} gün uzatıldı.")

            request.session.pop('trial_uzat_idler', None)
            return redirect('admin:kullanicilar_kullanici_changelist')

        context = dict(
            self.admin_site.each_context(request),
            kullanicilar=kullanicilar,
        )
        return render(request, 'admin/trial_uzat.html', context)

    def mail_gonder_ekrani(self, request):
        idler = request.session.get('mail_gonder_idler', [])
        kullanicilar = Kullanici.objects.filter(id__in=idler)

        if request.method == 'POST':
            secili_idler = request.POST.getlist('secili_id')
            konu = request.POST.get('konu', '').strip()
            mesaj = request.POST.get('mesaj', '').strip()
            hedefler = list(
                Kullanici.objects.filter(id__in=secili_idler).exclude(email='')
            )

            hata = None
            if not konu or not mesaj:
                hata = "Konu ve mesaj boş olamaz."
            elif not hedefler:
                hata = "Gönderilecek kullanıcı bulunamadı."
            elif len(hedefler) > MAIL_TEK_SEFERDE_MAX:
                hata = (f"Tek seferde en fazla {MAIL_TEK_SEFERDE_MAX} kullanıcıya "
                        f"gönderilebilir. Şu an {len(hedefler)} kullanıcı seçili.")

            if hata:
                messages.error(request, hata)
                context = dict(
                    self.admin_site.each_context(request),
                    kullanicilar=kullanicilar,
                    konu=konu,
                    mesaj=mesaj,
                    limit=MAIL_TEK_SEFERDE_MAX,
                )
                return render(request, 'admin/mail_gonder.html', context)

            # Herkese ayrı mail gider (kullanıcılar birbirinin adresini görmez).
            mailler = [EmailMessage(subject=konu, body=mesaj, to=[k.email]) for k in hedefler]
            try:
                baglanti = get_connection()
                gonderilen = baglanti.send_messages(mailler) or 0
            except Exception as e:
                messages.error(request, f"Mail gönderilemedi: {e}")
                return redirect('admin:kullanicilar_kullanici_changelist')

            request.session.pop('mail_gonder_idler', None)
            messages.success(request, f"{gonderilen} kullanıcıya mail gönderildi.")
            if gonderilen < len(hedefler):
                messages.warning(request, f"{len(hedefler) - gonderilen} mail gönderilemedi.")
            return redirect('admin:kullanicilar_kullanici_changelist')

        context = dict(
            self.admin_site.each_context(request),
            kullanicilar=kullanicilar,
            limit=MAIL_TEK_SEFERDE_MAX,
        )
        return render(request, 'admin/mail_gonder.html', context)


@admin.register(Ayarlar)
class AyarlarAdmin(admin.ModelAdmin):
    list_display = ('bakim_modu_acik', 'bakim_mesaji', 'bakim_max_versiyon')

    def has_add_permission(self, request):
        if Ayarlar.objects.exists():
            return False
        return True