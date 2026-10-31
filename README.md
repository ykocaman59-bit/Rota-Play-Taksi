# RotaPlay (şoför uygulaması)

Android araç ekranı başlatıcısı + taksi şoför modu. Veriler Firebase'dedir.

- Firebase kurulumu: `firebase/README.md`
- Şoför sayfasındaki akış: çevrimiçi ol → 3 km (30 sn sonra 5 km) içindeki talepler için bildirim → kabul → Yandex Navigatör yolcuya rota çizer → "Yolcuyu aldım" → varış adresine rota → "Yolculuğu bitir" → taksimetre tutarı → puanlama.
- Yolcu tarafı ayrı projedir: `RotaPlay-Yolcu` (aynı Firebase'e bağlanır).
- Ayar: `app/src/main/java/com/rotaplay/app/FirebaseConfig.kt`
