package com.warungtomyam.pos.data

/**
 * Static dictionary of curated café food-term translations.
 *
 * When `doNotTranslate` is false and no manual override is provided,
 * the system looks up the English term here to auto-fill BM/ZH/TA/TH.
 *
 * Design decision (REQ-9, REQ-12 Gap A):
 * - Zero paid translation APIs — all translations are bundled static strings.
 * - English is the authored source; other languages are dictionary-resolved
 *   with English fallback for any missing term.
 * - Common Malaysian/SE-Asian café food terms (~100+ entries).
 * - Lookup is case-insensitive on the English key.
 */
object FoodDictionary {

    /**
     * A single food-term entry with translations in all supported languages.
     */
    data class FoodTerm(
        val en: String,
        val bm: String,
        val zh: String,
        val ta: String,
        val th: String,
    )

    /**
     * Curated dictionary of common food stall terms.
     * Key: lowercase English name for O(1) lookup.
     * Covers: dish names, cooking methods, ingredients, modifiers, Thai terms.
     */
    private val dictionary: Map<String, FoodTerm> = listOf(
        // --- Dish names ---
        FoodTerm("Nasi Lemak", "Nasi Lemak", "椰浆饭", "நாசி லெமாக்", "ข้าวมันกะทิ"),
        FoodTerm("Roti Canai", "Roti Canai", "印度煎饼", "ரொட்டி சனாய்", "โรตีจาไน"),
        FoodTerm("Mee Goreng", "Mee Goreng", "炒面", "மீ கோரெங்", "หมี่กอเร็ง"),
        FoodTerm("Teh Tarik", "Teh Tarik", "拉茶", "தேநீர் தாரிக்", "ชาดึง"),
        FoodTerm("Kopi", "Kopi", "咖啡", "கோப்பி", "กาแฟ"),
        FoodTerm("Kopi O", "Kopi O", "咖啡乌", "கோப்பி ஓ", "กาแฟดำ"),
        FoodTerm("Nasi Goreng", "Nasi Goreng", "炒饭", "நாசி கோரெங்", "ข้าวผัด"),
        FoodTerm("Ayam Goreng", "Ayam Goreng", "炸鸡", "பொரித்த கோழி", "ไก่ทอด"),
        FoodTerm("Ikan Bakar", "Ikan Bakar", "烤鱼", "சுட்ட மீன்", "ปลาย่าง"),
        FoodTerm("Tom Yam", "Tom Yam", "冬炎汤", "தோம் யாம்", "ต้มยำ"),
        FoodTerm("Nasi Ayam", "Nasi Ayam", "鸡饭", "சிக்கன் ரைஸ்", "ข้าวไก่"),
        FoodTerm("Mee Rebus", "Mee Rebus", "卤面", "மீ ரெபுஸ்", "หมี่เรอบุส"),
        FoodTerm("Laksa", "Laksa", "叻沙", "லக்சா", "ลักซา"),
        FoodTerm("Satay", "Satay", "沙爹", "சதே", "สะเต๊ะ"),
        FoodTerm("Rendang", "Rendang", "仁当", "ரெண்டாங்", "เรินดัง"),
        FoodTerm("Rojak", "Rojak", "罗惹", "ரோஜாக்", "โรจัก"),
        FoodTerm("Cendol", "Cendol", "煎蕊", "செண்டோல்", "เชนดอล"),
        FoodTerm("Air Sirap", "Air Sirap", "玫瑰糖浆水", "ஏர் சிரப்", "น้ำหวานกุหลาب"),
        FoodTerm("Teh O Ais", "Teh O Ais", "冰红茶", "தேநீர் ஓ ஐஸ்", "ชาดำเย็น"),
        FoodTerm("Milo", "Milo", "美禄", "மைலோ", "ไมโล"),
        FoodTerm("Nasi Biryani", "Nasi Biryani", "印度香饭", "பிரியாணி", "ข้าวบิรยานี"),
        FoodTerm("Sup Ayam", "Sup Ayam", "鸡汤", "சிக்கன் சூப்", "ซุปไก่"),
        FoodTerm("Telur Mata", "Telur Mata", "煎蛋", "பொரித்த முட்டை", "ไข่ดาว"),
        FoodTerm("Keropok", "Keropok", "虾饼", "கெரோப்போக்", "ข้าวเกรียบ"),
        FoodTerm("Kangkung", "Kangkung", "空心菜", "கங்குங்", "ผักบุ้ง"),
        FoodTerm("Tahu Goreng", "Tahu Goreng", "炸豆腐", "பொரித்த தோஃபு", "เต้าหู้ทอด"),
        FoodTerm("Pisang Goreng", "Pisang Goreng", "炸香蕉", "வாழைப்பழம் பொரியல்", "กล้วยทอด"),
        FoodTerm("Ais Kacang", "Ais Kacang", "红豆冰", "ஐஸ் கச்சாங்", "น้ำแข็งใส"),
        FoodTerm("Kuih", "Kuih", "糕点", "குய்", "ขนม"),
        FoodTerm("Asam Pedas", "Asam Pedas", "酸辣鱼", "ஆசம் பெடாஸ்", "แกงเปรี้ยวเผ็ด"),
        FoodTerm("Ketupat", "Ketupat", "椰叶饭", "கெடுபட்", "เกตุปัต"),
        FoodTerm("Lontong", "Lontong", "椰浆饭块", "லொண்டோங்", "ลอนตง"),
        FoodTerm("Gulai", "Gulai", "咖喱汤", "குலாய்", "แกง"),
        FoodTerm("Bubur", "Bubur", "粥", "கஞ்சி", "โจ๊ก"),
        // --- Cooking methods ---
        FoodTerm("Goreng", "Goreng", "炸", "வறுத்த", "ทอด"),
        FoodTerm("Rebus", "Rebus", "煮", "கிழித்த", "ต้ม"),
        FoodTerm("Kukus", "Kukus", "蒸", "வெந்த", "นึ่ง"),
        FoodTerm("Bakar", "Bakar", "烤", "சுட்ட", "ย่าง"),
        FoodTerm("Panggang", "Panggang", "烤焙", "பாங்", "ย่าง"),
        FoodTerm("Masak", "Masak", "煮", "சமை", "ทำอาหาร"),
        // --- Flavour modifiers ---
        FoodTerm("Pedas", "Pedas", "辣", "காரம்", "เผ็ด"),
        FoodTerm("Kurang Pedas", "Kurang Pedas", "少辣", "குறைந்த காரம்", "เผ็ดน้อย"),
        FoodTerm("Manis", "Manis", "甜", "இனிப்பு", "หวาน"),
        FoodTerm("Masin", "Masin", "咸", "உப்பு", "เค็ม"),
        FoodTerm("Masam", "Masam", "酸", "புளிப்பு", "เปรี้ยว"),
        FoodTerm("Lemak", "Lemak", "香浓", "கொழுப்பு", "มัน"),
        FoodTerm("Kurang Garam", "Kurang Garam", "少盐", "உப்பு குறைவு", "เค็มน้อย"),
        // --- Ingredients (protein & seafood) ---
        FoodTerm("Telur", "Telur", "蛋", "முட்டை", "ไข่"),
        FoodTerm("Ikan", "Ikan", "鱼", "மீன்", "ปลา"),
        FoodTerm("Udang", "Udang", "虾", "சீப்பங்க்", "กุ้ง"),
        FoodTerm("Sotong", "Sotong", "鱿鱼", "சோட்டோங்", "ปลาหมึก"),
        FoodTerm("Ketam", "Ketam", "螃蟹", "கிராப்", "ปู"),
        FoodTerm("Kupang", "Kupang", "青口贝", "குபாங்", "หอยแมลงภู่"),
        FoodTerm("Kerang", "Kerang", "蛤蜊", "கெராங்", "หอย"),
        FoodTerm("Siakap", "Siakap", "金目鲈", "சியாகப்", "ปลากะพง"),
        FoodTerm("Kembung", "Kembung", "竹荚鱼", "கெம்புங்", "ปลาทู"),
        FoodTerm("Tongkol", "Tongkol", "鲭鱼", "தொங்கோல்", "ปลาโอ"),
        FoodTerm("Ikan Tenggiri", "Ikan Tenggiri", "马鲛鱼", "தெங்கிரி மீன்", "ปลาอินทรี"),
        FoodTerm("Ikan Pari", "Ikan Pari", "魟鱼", "இகான் பாரி", "ปลากระเบน"),
        FoodTerm("Kepala Ikan", "Kepala Ikan", "鱼头", "மீன் தலை", "หัวปลา"),
        // --- Ingredients (vegetables & staples) ---
        FoodTerm("Nasi", "Nasi", "饭", "அரிசி", "ข้าว"),
        FoodTerm("Sayur", "Sayur", "菜", "காய்கறி", "ผัก"),
        FoodTerm("Kailan", "Kailan", "芥兰", "கைலான்", "คะน้า"),
        FoodTerm("Tauhu", "Tauhu", "豆腐", "தோஃபு", "เต้าหู้"),
        FoodTerm("Tempe", "Tempe", "天贝", "டெம்பே", "เทมเป้"),
        // --- Condiments & aromatics ---
        FoodTerm("Sambal", "Sambal", "参巴酱", "சம்பல்", "ซัมบัล"),
        FoodTerm("Sambal Belacan", "Sambal Belacan", "虾酱辣椒", "சம்பல் பெலகன்", "ซัมบัลกะปิ"),
        FoodTerm("Belacan", "Belacan", "虾酱", "பெலகன்", "กะปิ"),
        FoodTerm("Cili", "Cili", "辣椒", "மிளகாய்", "พริก"),
        FoodTerm("Halia", "Halia", "姜", "இஞ்சி", "ขิง"),
        FoodTerm("Serai", "Serai", "香茅", "லெமொங்", "ตะไคร้"),
        FoodTerm("Daun Limau", "Daun Limau", "酸橙叶", "லிமா இலை", "ใบมะกรูด"),
        FoodTerm("Pandan", "Pandan", "香兰叶", "பாண்டன்", "ใบเตย"),
        FoodTerm("Asam", "Asam", "酸", "புளி", "เปรี้ยว"),
        FoodTerm("Kari", "Kari", "咖喱", "கறி", "แกง"),
        FoodTerm("Kicap Manis", "Kicap Manis", "甜酱油", "கிச்சாப் இனிப்பு", "ซีอิ๊วหวาน"),
        FoodTerm("Kicap Pekat", "Kicap Pekat", "浓酱油", "கிச்சாப் கரை", "ซีอิ๊วดำ"),
        FoodTerm("Gula Melaka", "Gula Melaka", "椰糖", "மலாக்கா சர்க்கரை", "น้ำตาลมะลิ"),
        FoodTerm("Sos", "Sos", "酱", "சாஸ்", "ซอส"),
        FoodTerm("Perencah", "Perencah", "调味料", "சுவைமசாலா", "เครื่องปรุง"),
        FoodTerm("Kuah Kacang", "Kuah Kacang", "花生酱汁", "பீனட் சாஸ்", "น้ำถั่ว"),
        // --- Soup / gravy types ---
        FoodTerm("Kuah", "Kuah", "汤汁", "சாறு", "น้ำแกง"),
        FoodTerm("Kuah Pekat", "Kuah Pekat", "浓汤", "கடுகு சாறு", "น้ำแกงข้น"),
        FoodTerm("Kuah Cair", "Kuah Cair", "稀汤", "இரசம்", "น้ำแกงใส"),
        FoodTerm("Sup", "Sup", "汤", "சூப்", "ซุป"),
        // --- Colour / descriptor modifiers ---
        FoodTerm("Merah", "Merah", "红", "சிவப்பு", "แดง"),
        FoodTerm("Putih", "Putih", "白", "வெள்ளை", "ขาว"),
        FoodTerm("Campur", "Campur", "混合", "கலவை", "รวม"),
        FoodTerm("Kosong", "Kosong", "空", "காலி", "เปล่า"),
        // --- General modifiers / operational ---
        FoodTerm("Tanpa", "Tanpa", "无", "இல்லாமல்", "ไม่มี"),
        FoodTerm("Tidak", "Tidak", "不", "இல்லை", "ไม่"),
        FoodTerm("Tanpa Bawang", "Tanpa Bawang", "不加洋葱", "வெங்காயமின்றி", "ไม่ใส่หอม"),
        FoodTerm("Tambahan", "Tambahan", "加点", "கூடுதல்", "เพิ่ม"),
        FoodTerm("Tambahan Nasi", "Tambahan Nasi", "加饭", "அரிசி கூடுதல்", "เพิ่มข้าว"),
        FoodTerm("Sedia", "Sedia", "现成", "தயார்", "พร้อม"),
        FoodTerm("Segar", "Segar", "新鲜", "புதிய", "สด"),
        FoodTerm("Bungkus", "Bungkus", "打包", "பேக்கிங்", "ห่อกลับบ้าน"),
        FoodTerm("Makan", "Makan", "吃", "சாப்பிடு", "กิน"),
        FoodTerm("Halal", "Halal", "清真", "ஹலால்", "ฮாலาல"),
        FoodTerm("Air", "Air", "水", "தண்ணீர்", "น้ำ"),
        FoodTerm("Lauk", "Lauk", "配菜", "லாக்", "กับข้าว"),
        FoodTerm("Kerabu", "Kerabu", "凉拌", "கெராபு", "ยำ"),
        FoodTerm("Nyonya", "Nyonya", "娘惹", "நியோன்யா", "นิโอนยา"),
        FoodTerm("Perut", "Perut", "肚", "குடல்", "เครื่องใน"),
        // --- Thai terms (cross-language support) ---
        FoodTerm("Pad", "Goreng", "炒", "வறுத்த", "ผัด"),
        FoodTerm("Tom", "Rebus/Tom", "汤/煮", "தோம்", "ต้ม"),
        FoodTerm("Gaeng", "Kari/Kuah", "咖喱/汤", "கறி", "แกง"),
        FoodTerm("Pad Thai", "Pad Thai", "泰式炒河", "பாட் தாய்", "ผัดไทย"),
        FoodTerm("Tom Yum", "Tom Yam", "冬阴功", "தோம் யாம்", "ต้มยำ"),
        FoodTerm("Tom Kha", "Tom Kha", "椰奶汤", "தோம் கா", "ต้มข่า"),
        FoodTerm("Khao", "Nasi", "饭", "அரிசி", "ข้าว"),
        FoodTerm("Khao Niao", "Pulut", "糯米", "காவ் நியாவ்", "ข้าวเหนียว"),
        FoodTerm("Nam", "Air/Nam", "水/汁", "நீர்", "น้ำ"),
        FoodTerm("Ped", "Ped/Spicy", "辣", "காரம்", "เผ็ด"),
        FoodTerm("Mai Ped", "Tidak Pedas", "不辣", "காரம் இல்லை", "ไม่เผ็ด"),
        FoodTerm("Prik", "Cili", "辣椒", "மிளகாய்", "พริก"),
        FoodTerm("Gai", "Ayam", "鸡", "கோழி", "ไก่"),
        FoodTerm("Moo", "Daging Babi", "猪肉", "பன்றி இறைச்சி", "หมู"),
        FoodTerm("Neua", "Daging Lembu", "牛肉", "மாட்டிறைச்சி", "เนื้อ"),
        FoodTerm("Goong", "Udang", "虾", "சீப்பங்க்", "กุ้ง"),
        FoodTerm("Pla", "Ikan", "鱼", "மீன்", "ปลา"),
        FoodTerm("Talay", "Makanan Laut", "海鲜", "கடல் உணவு", "ทะเล"),
        FoodTerm("Tod", "Goreng/Deep-fried", "炸", "வறுத்த", "ทอด"),
        FoodTerm("Yang", "Bakar/Grilled", "烤", "அவித்த", "ย่าง"),
        FoodTerm("Neung", "Kukus/Steamed", "蒸", "வெந்த", "นึ่ง"),
        FoodTerm("Nam Pla", "Sos Ikan", "鱼露", "மீன் சாஸ்", "น้ำปลา"),
        FoodTerm("Nam Jim", "Sos Celup", "蘸酱", "சாஸ்", "น้ำจิ้ม"),
        FoodTerm("Jay", "Vegetarian (Jay)", "素食（เจ）", "சைவம்", "เจ"),
        FoodTerm("Khanom", "Pencuci Mulut", "甜点", "இனிப்பு", "ขนม"),
        FoodTerm("Gaeng Daeng", "Kari Merah", "红咖喱", "கறி சிவப்பு", "แกงแดง"),
        FoodTerm("Gaeng Keow Wan", "Kari Hijau", "绿咖喱", "கறி பச்சை", "แกงเขียวหวาน"),
        FoodTerm("Som Tam", "Kerabu Betik", "青木瓜沙拉", "சோம் தாம்", "ส้มตำ"),
    ).associateBy { it.en.lowercase() }

    /**
     * Look up translations for an English food term.
     * Returns null if the term is not in the dictionary (English fallback applies).
     *
     * @param englishName The English name to look up (case-insensitive).
     * @return A [FoodTerm] with all translations, or null if not found.
     */
    fun lookup(englishName: String): FoodTerm? {
        return dictionary[englishName.trim().lowercase()]
    }

    /**
     * Get the translation for a specific language, falling back to English if
     * the term is not in the dictionary.
     *
     * @param englishName The English source name.
     * @param lang Target language code: "bm", "zh", "ta", "th".
     * @return Translated name or the original English name if not found.
     */
    fun translate(englishName: String, lang: String): String {
        val term = lookup(englishName) ?: return englishName
        return when (lang.lowercase()) {
            "bm", "ms" -> term.bm
            "zh" -> term.zh
            "ta" -> term.ta
            "th" -> term.th
            else -> term.en
        }
    }

    /**
     * Get all translations for a term as a map.
     * Returns null if the term is not in the dictionary.
     *
     * @param englishName The English source name.
     * @return Map of lang code → translated name, or null if not found.
     */
    fun translateAll(englishName: String): Map<String, String>? {
        val term = lookup(englishName) ?: return null
        return mapOf(
            "en" to term.en,
            "bm" to term.bm,
            "zh" to term.zh,
            "ta" to term.ta,
            "th" to term.th,
        )
    }

    /**
     * Check if a term exists in the dictionary.
     */
    fun contains(englishName: String): Boolean {
        return dictionary.containsKey(englishName.trim().lowercase())
    }

    /**
     * All dictionary entries. Useful for admin UI autocomplete suggestions.
     */
    fun allTerms(): List<FoodTerm> = dictionary.values.toList()
}
