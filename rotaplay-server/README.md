# RotaPlay Taksi Sunucusu

Ek paket gerektirmez, sadece Node.js 18+.

    node server.js                       # http://localhost:3000  (yolcu sayfası burada açılır)
    ADMIN_KEY=gizli-bir-anahtar node server.js
    AUTO_APPROVE=1 node server.js        # sadece test: şoförleri otomatik onayla
    ./test.sh                            # uçtan uca test

## Şoför onaylama
    curl -H "x-admin-key: gizli-bir-anahtar" http://localhost:3000/api/admin/drivers
    curl -X POST -H "x-admin-key: gizli-bir-anahtar" -H "content-type: application/json" \
         -d '{"id":"ŞOFÖR_ID"}' http://localhost:3000/api/admin/approve

## Her şoföre özel adres
Kayıtta şoföre `id` ve `apiKey` verilir. Şoförün adresi `/api/driver/<id>/...` olur ve yalnızca kendi anahtarıyla çalışır.

## Canlıya alırken
- HTTPS arkasında çalıştır (Caddy/nginx + Let's Encrypt). Yolcu sayfasında konum izni https ister.
- ADMIN_KEY değiştir.
- RotaPlay'de `TaxiStore.kt` içindeki `TAXI_SERVER` adresini güncelle, Manifest'ten `usesCleartextTraffic` satırını sil.
