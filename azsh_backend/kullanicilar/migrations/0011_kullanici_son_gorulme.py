from django.db import migrations, models
from django.db.models import Max


def eski_kayitlari_doldur(apps, schema_editor):
    """Daha önce bağlanmış kullanıcılar için, cihazlarının son erişim tarihini
    'son bağlantı' olarak kullan (cihaz tarihi her erişimde güncelleniyordu)."""
    Kullanici = apps.get_model('kullanicilar', 'Kullanici')
    Cihaz = apps.get_model('kullanicilar', 'Cihaz')
    for satir in Cihaz.objects.values('kullanici_id').annotate(son=Max('eklenme_tarihi')):
        Kullanici.objects.filter(pk=satir['kullanici_id']).update(son_gorulme=satir['son'])


class Migration(migrations.Migration):

    dependencies = [
        ('kullanicilar', '0010_ayarlar_bakim_url'),
    ]

    operations = [
        migrations.AddField(
            model_name='kullanici',
            name='son_gorulme',
            field=models.DateTimeField(blank=True, db_index=True, null=True, verbose_name='Son Bağlantı'),
        ),
        migrations.RunPython(eski_kayitlari_doldur, migrations.RunPython.noop),
    ]
