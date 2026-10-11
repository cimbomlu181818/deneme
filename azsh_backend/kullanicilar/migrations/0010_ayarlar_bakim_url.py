from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('kullanicilar', '0009_ayarlar_bakim_max_versiyon'),
    ]

    operations = [
        migrations.AddField(
            model_name='ayarlar',
            name='bakim_url',
            field=models.URLField(blank=True, null=True, verbose_name='Bakım Modu - Buton URL'),
        ),
    ]
