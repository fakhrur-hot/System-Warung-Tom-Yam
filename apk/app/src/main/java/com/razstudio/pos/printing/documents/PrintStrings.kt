package com.razstudio.pos.printing.documents

/**
 * Fixed labels printed on kitchen slips and receipts, in all 5 supported print languages
 * (BM/EN/ZH/TA/TH). Latin (BM/EN) prints as ESC/POS text; ZH/TA/TH are rendered via the
 * bitmap fallback (see BitmapTicketRenderer), so their glyphs print correctly.
 *
 * The RAZStudio footer is intentionally NOT here — it is fixed and never translated.
 */
data class PrintStrings(
    val table: String,
    val date: String,
    val session: String,      // word only; documents format it as "<session> #<n>"
    val food: String,
    val beverages: String,
    val sideDishes: String,
    val others: String,
    val added: String,
    val total: String,
    val payment: String,
    val cash: String,
    val thankYou: String,
)

/** Resolve print labels for a print-language code (BM/EN/ZH/TA/TH). Defaults to EN. */
fun printStrings(lang: String): PrintStrings = when (lang.uppercase()) {
    "BM", "MY" -> PrintStrings(
        table = "Meja", date = "Tarikh", session = "Sesi",
        food = "MAKANAN", beverages = "MINUMAN", sideDishes = "Lauk", others = "Lain-lain",
        added = "TAMBAHAN", total = "JUMLAH", payment = "Bayaran", cash = "Tunai",
        thankYou = "Terima Kasih",
    )
    "ZH" -> PrintStrings(
        table = "桌号", date = "日期", session = "场次",
        food = "食物", beverages = "饮料", sideDishes = "配菜", others = "其他",
        added = "追加", total = "总计", payment = "付款", cash = "现金",
        thankYou = "谢谢惠顾",
    )
    "TA" -> PrintStrings(
        table = "மேசை", date = "தேதி", session = "அமர்வு",
        food = "உணவு", beverages = "பானங்கள்", sideDishes = "பக்க உணவு", others = "மற்றவை",
        added = "கூடுதல்", total = "மொத்தம்", payment = "கட்டணம்", cash = "பணம்",
        thankYou = "நன்றி",
    )
    "TH" -> PrintStrings(
        table = "โต๊ะ", date = "วันที่", session = "รอบ",
        food = "อาหาร", beverages = "เครื่องดื่ม", sideDishes = "กับข้าว", others = "อื่นๆ",
        added = "เพิ่มเติม", total = "รวม", payment = "การชำระเงิน", cash = "เงินสด",
        thankYou = "ขอบคุณ",
    )
    else -> PrintStrings(
        table = "Table", date = "Date", session = "Session",
        food = "FOOD", beverages = "BEVERAGES", sideDishes = "Side Dishes", others = "Others",
        added = "ADDED", total = "TOTAL", payment = "Payment", cash = "Cash",
        thankYou = "Thank You",
    )
}
