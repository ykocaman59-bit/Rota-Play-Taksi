# RotaPlay Sarı Taksi (şoför uygulaması)

Android araç ekranı başlatıcısı + **sarı taksi** şoför modu. Veriler Firebase'dedir.

- Firebase kurulumu: `firebase/README.md` (bu güncellemede kuralları yeniden yayımlamayı unutma)
- Kayıt: ad soyad, plaka, telefon, **Gmail (doğrulama e-postası)**, **IBAN**, şifre. Belgeler onaylanınca talep alınır.
- Şoför IBAN'ı kayıtta girilir, ana ekrandan düzenlenebilir; yolcu şoför profilinde görür ve kopyalar.
- Akış: çevrimiçi ol → talep → kabul → Yandex yolcuya rota → "Yolcuyu aldım" → varış rotası → bitir → taksimetre tutarı → puan.
- Yolcu tarafı: `RotaPlay-Yolcu` (aynı Firebase).
