#!/bin/bash
cd "$(dirname "$0")"; export PORT=3998 AUTO_APPROVE=1 DB_FILE=/tmp/t2.json; rm -f $DB_FILE
node server.js >/dev/null 2>&1 & S=$!; sleep 1; B=http://localhost:3998
j() { curl -s -X POST "$B$1" -H 'content-type: application/json' ${3:+-H "Authorization: Bearer $3"} -d "$2"; }
f() { node -pe "JSON.parse(require('fs').readFileSync(0)).$1"; }
A=$(j /api/driver/register '{"name":"Ali","phone":"1","plate":"A","password":"123456"}'); C=$(j /api/driver/register '{"name":"Can","phone":"2","plate":"B","password":"123456"}')
AI=$(echo $A|f id); AK=$(echo $A|f apiKey); CI=$(echo $C|f id); CK=$(echo $C|f apiKey)
curl -sN -H "Authorization: Bearer $AK" $B/api/driver/$AI/stream >/tmp/a2.sse & P1=$!
curl -sN -H "Authorization: Bearer $CK" $B/api/driver/$CI/stream >/tmp/c2.sse & P2=$!; sleep 0.5
j /api/driver/$AI/online '{"online":true,"lat":41.01,"lon":28.97}' $AK >/dev/null; j /api/driver/$CI/online '{"online":true,"lat":41.02,"lon":28.98}' $CK >/dev/null
R=$(j /api/rides '{"name":"Ayşe","phone":"5","pickup":{"lat":41.011,"lon":28.975},"dest":{"lat":41.04,"lon":29}}'); RID=$(echo $R|f id); TOK=$(echo $R|f token); sleep 0.4
j /api/driver/$AI/rides/$RID/accept '{}' $AK >/dev/null
echo "--- Şoför A iptal:"; j /api/driver/$AI/rides/$RID/cancel '{}' $AK; echo; sleep 0.4
echo "--- Yolcu durumu (searching + driverCancelled bekleniyor):"; curl -s "$B/api/rides/$RID?token=$TOK" | node -pe "const r=JSON.parse(require('fs').readFileSync(0));r.status+' '+r.driverCancelled"
echo "--- C'ye yeniden gitti mi (ride olay sayısı 2 bekleniyor):"; grep -c '^event: ride$' /tmp/c2.sse
echo "--- A'ya tekrar gitti mi (1 kalmalı):"; grep -c '^event: ride$' /tmp/a2.sse
echo "--- C kabul:"; j /api/driver/$CI/rides/$RID/accept '{}' $CK | f status
echo "--- Yolcu iptal (C'ye ride_cancelled):"; j /api/rides/$RID/cancel "{\"token\":\"$TOK\"}"; echo; sleep 0.3; grep -c ride_cancelled /tmp/c2.sse
j /api/driver/$CI/rides/$RID/pickup '{}' $CK
kill $P1 $P2 $S 2>/dev/null
