package com.rotaplay.app

/**
 * Türkiye (81 il) ticari taksi plakası doğrulaması.
 *
 *  - İl kodu 01–81
 *  - İl kodundan hemen sonra gelen harf grubu "T" ile başlar (T, TA, TAA ...), en fazla 3 harf
 *  - Ardından 2–4 rakam
 *  - Plakalarda Q, W, X harfleri kullanılmaz (Türkiye plaka sisteminde yoktur)
 *
 * Geçerli: 34 T 1234 · 06 TAA 12 · 16 T 555 · 34T1234 · 34-t-1234
 * Reddedilir: 34 BAB 34 · 34 ATB 12 · 00 T 123 · 82 T 123 · 34 T 1
 *
 * NOT: Bu yalnızca BİÇİM kontrolüdür; plakanın gerçekten bir taksiye ait olduğunu kanıtlamaz.
 * Asıl doğrulama ruhsat/OCR kontrolü ve yönetici incelemesidir.
 */
object TaxiPlate {
    /** Boşluk, tire vb. atıldıktan sonra büyük harfli (aralıksız) plaka üzerinde çalışır. */
    private val COMPACT = Regex("^(0[1-9]|[1-7][0-9]|8[01])(T[A-PR-VYZ]{0,2})([0-9]{2,4})$")

    /** Sadece harf+rakam bırakır, büyük harfe çevirir (Kotlin uppercase() dil ayarından bağımsızdır). */
    fun compact(raw: String): String = raw.uppercase().replace(Regex("[^0-9A-Z]"), "")

    fun isValid(raw: String): Boolean = COMPACT.matches(compact(raw))

    /** Geçerliyse "34 T 1234" biçiminde döner, değilse null. */
    fun format(raw: String): String? {
        val m = COMPACT.matchEntire(compact(raw)) ?: return null
        return "${m.groupValues[1]} ${m.groupValues[2]} ${m.groupValues[3]}"
    }

    /** Forma yazılan metin hata mesajı gerektiriyorsa mesajı, yoksa null döner. */
    fun errorFor(raw: String): String? = when {
        raw.isBlank() -> null
        isValid(raw) -> null
        else -> "Geçersiz plaka. Yalnızca ticari taksi plakası girilebilir (il kodu 01-81, ardından T ile başlayan harf grubu). Örn: 34 T 1234, 06 TAA 12"
    }
}

/** Zorunlu yasal sorumluluk metni. Metin değişirse VERSION'ı artırın. */
object LegalConsent {
    const val VERSION = "v1"
    const val TEXT =
        "Bu uygulamanın yalnızca ruhsatlı ticari taksi sürücülerine hizmet verdiğini, sisteme girdiğim plakanın şahsıma ve tescilli ticari aracıma ait olduğunu kabul ediyorum. " +
        "Hususi araçlarla veya gerçeğe aykırı/sahte plaka beyanıyla korsan taşımacılık yapılması durumunda tüm idari, hukuki ve cezai sorumluluğun şahsıma ait olduğunu, " +
        "platform sahibinin bu durumdan sorumlu tutulamayacağını okudum ve onaylıyorum."
}
