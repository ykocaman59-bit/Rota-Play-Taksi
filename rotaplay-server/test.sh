#!/bin/bash
# Uçtan uca test: iki şoför aynı yolculuğu kabul etmeye çalışır, yalnızca biri alabilmeli
cd "$(dirname "$0")"; export PORT=3999 AUTO_APPROVE=1 DB_FILE=/tmp/t.json; rm -f $DB_FILE
node server.js >/tmp/srv.log 2>&1 & S=$!; sleep 1; B=http://localhost:3999
j() { curl -s -X POST "$B$1" -H 'content-type: application/json' ${3:+-H "Authorization: Bearer $3"} -d "$2"; }
A=$(j /api/driver/register '{"name":"Ali Veli","phone":"501","plate":"34 TX 100","password":"123456"}')
C=$(j /api/driver/register '{"name":"Can Kaya","phone":"502","plate":"34 TX 200","password":"123456"}')
AI=$(echo $A | node -pe 'JSON.parse(require("fs").readFileSync(0)).id'); AK=$(echo $A | node -pe 'JSON.parse(require("fs").readFileSync(0)).apiKey')
CI=$(echo $C | node -pe 'JSON.parse(require("fs").readFileSync(0)).id'); CK=$(echo $C | node -pe 'JSON.parse(require("fs").readFileSync(0)).apiKey')
curl -sN -H "Authorization: Bearer $AK" $B/api/driver/$AI/stream >/tmp/a.sse & P1=$!
curl -sN -H "Authorization: Bearer $CK" $B/api/driver/$CI/stream >/tmp/c.sse & P2=$!
sleep 0.5
j /api/driver/$AI/online '{"online":true,"lat":41.01,"lon":28.97}' $AK; j /api/driver/$CI/online '{"online":true,"lat":41.02,"lon":28.98}' $CK; echo
R=$(j /api/rides '{"name":"Ayşe Y","phone":"555","pickup":{"lat":41.011,"lon":28.975,"text":"Sultanahmet"},"dest":{"lat":41.04,"lon":29.0,"text":"Beşiktaş"}}')
RID=$(echo $R | node -pe 'JSON.parse(require("fs").readFileSync(0)).id'); TOK=$(echo $R | node -pe 'JSON.parse(require("fs").readFileSync(0)).token'); sleep 0.5
echo "--- A akışı:"; grep -c '^event: ride$' /tmp/a.sse; echo "--- Kabul A:"; j /api/driver/$AI/rides/$RID/accept '{}' $AK | head -c 150; echo
echo "--- Kabul C (409 beklenir):"; j /api/driver/$CI/rides/$RID/accept '{}' $CK; echo
sleep 0.3; echo "--- C ride_gone aldı mı:"; grep -c 'ride_gone' /tmp/c.sse
j /api/driver/$AI/rides/$RID/pickup '{}' $AK >/dev/null
echo "--- Fare'siz bitirme (400 beklenir):"; j /api/driver/$AI/rides/$RID/complete '{}' $AK; echo
j /api/driver/$AI/rides/$RID/complete '{"fare":187.5}' $AK | head -c 120; echo
echo "--- Yolcu puanı:"; j /api/rides/$RID/rate "{\"token\":\"$TOK\",\"rating\":5}"; echo
j /api/driver/$AI/rides/$RID/rate '{"rating":4}' $AK; echo
echo "--- Şoför profili:"; curl -s -H "Authorization: Bearer $AK" $B/api/driver/$AI/me; echo
echo "--- Yanlış anahtar (401):"; curl -s -H "Authorization: Bearer $AK" $B/api/driver/$CI/me; echo
kill $P1 $P2 $S 2>/dev/null
