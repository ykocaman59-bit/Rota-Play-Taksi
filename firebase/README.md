# Firebase kurulumu (şoför ve yolcu projeleri aynı Firebase'i kullanır)

Bu klasör `RotaPlay-Sofor` projesindedir; Firebase'i bir kez kurarsın, `RotaPlay-Yolcu` aynı projeye bağlanır.

1. console.firebase.google.com → **Proje oluştur** (Analytics kapalı).
2. **Build → Authentication → Sign-in method**: **Email/Password** ve **Anonymous** aç.
3. **Build → Firestore Database → Create database** → **Production mode**, Avrupa'dan bir konum (sonradan değişmez).
4. Firestore → **Rules** → `firestore.rules` içeriğini yapıştır → **Publish**.
5. Proje ayarları (çark) → **Your apps → Web (`</>`)** ekle. `apiKey`, `authDomain`, `projectId`, `appId` değerlerini al:
   - Bu projede: `app/src/main/java/com/rotaplay/app/FirebaseConfig.kt` (apiKey ve projectId)
   - Yolcu projesinde: `docs/config.js`
6. **Yönetici hesabı:** Authentication → Users → **Add user** (e-posta + şifre) → çıkan **User UID**'yi kopyala.
   Firestore → **Start collection** → `admins` → Document ID = UID → herhangi bir alan (örn. `ok` = true).
7. Authentication → Settings → **Authorized domains** → `KULLANICIADIN.github.io` ekle.

## Mesafe kuralı
Talep, yolcunun konumuna **3 km** içindeki çevrimiçi şoförlere gösterilir. 30 saniye içinde kabul eden olmazsa **5 km**'ye genişler,
90 saniyede süresi dolar. Şoförün konumu kabul sırasında talebe yazılır ve Firestore kuralı 5 km dışını reddeder.
Not: Ayrı bir sunucu olmadığı için şoförün bildirdiği konum telefondan gelir; uygulamayı kurcalayan biri sahte konum yollayabilir.

## Ücretsiz kota (Spark)
Günde 50.000 okuma, 20.000 yazma, 1 GiB. Şoför uygulaması talepleri 6 sn'de bir sorar: çevrimiçi her saat ≈ 600 okuma.
10 şoför × günde 8 saat çevrimiçi kotayı doldurur; fazlası için Blaze (kullandıkça öde) planı gerekir.
