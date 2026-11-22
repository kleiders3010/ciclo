package com.jurisgames.ciclo.ui

/**
 * The words for the cycle journal's categories and tags (the document holds ids only: "pain.cramps").
 * Order is the order on screen. Spanish is in [ES_CYCLE].
 */
object Words {
    val CATEGORIES: List<Pair<String, String>> = listOf(
        "pain" to "Pain", "feel" to "Feelings", "energy" to "Energy", "sleep" to "Sleep", "skin" to "Skin",
        "digest" to "Digestion and appetite", "sex" to "Sex and drive", "mucus" to "Discharge", "vulva" to "Vulva and vagina",
        "urine" to "Peeing", "tests" to "Tests", "meds" to "Medicine", "move" to "Exercise", "social" to "Life",
        "peri" to "Menopause signs", "mine" to "My tags",
    )

    val TAGS: Map<String, List<Pair<String, String>>> = mapOf(
        "pain" to listOf("cramps" to "Cramps", "headache" to "Headache", "migraine" to "Migraine", "ovulation" to "Ovulation pain",
            "breasts" to "Tender breasts", "back" to "Back pain", "joints" to "Joint pain", "stomach" to "Stomach ache"),
        "feel" to listOf("happy" to "Happy", "calm" to "Calm", "sensitive" to "Sensitive", "sad" to "Sad", "anxious" to "Anxious",
            "irritable" to "Irritable", "swings" to "Mood swings", "pms" to "PMS"),
        "energy" to listOf("high" to "High energy", "normal" to "Normal energy", "low" to "Low energy", "exhausted" to "Exhausted"),
        "sleep" to listOf("good" to "Slept well", "restless" to "Restless", "insomnia" to "Couldn't sleep", "a_lot" to "Slept a lot", "dreams" to "Vivid dreams"),
        "skin" to listOf("good" to "Clear skin", "acne" to "Acne", "oily" to "Oily skin", "dry" to "Dry skin", "itchy" to "Itchy skin"),
        "digest" to listOf("bloating" to "Bloating", "nausea" to "Nausea", "cravings" to "Cravings", "gas" to "Gas", "constipation" to "Constipation",
            "diarrhea" to "Diarrhea", "appetite_up" to "Hungrier", "appetite_down" to "Less hungry"),
        "sex" to listOf("protected" to "Protected sex", "unprotected" to "Unprotected sex", "withdrawal" to "Withdrawal", "solo" to "Masturbation",
            "drive_high" to "High sex drive", "drive_low" to "Low sex drive", "pain" to "Pain during sex"),
        "mucus" to listOf("dry" to "None / dry", "sticky" to "Sticky", "creamy" to "Creamy", "eggwhite" to "Egg white", "watery" to "Watery", "unusual" to "Unusual"),
        "vulva" to listOf("itchy" to "Itchy", "dry" to "Dry", "swollen" to "Swollen", "odor" to "Unusual smell", "pain" to "Sore"),
        "urine" to listOf("often" to "Peeing often", "burning" to "Burning", "leaks" to "Leaks"),
        "tests" to listOf("ov_pos" to "Ovulation test +", "ov_neg" to "Ovulation test −", "preg_pos" to "Pregnancy test +", "preg_neg" to "Pregnancy test −"),
        "meds" to listOf("painkiller" to "Painkiller", "antibiotic" to "Antibiotic", "antihistamine" to "Antihistamine", "vitamins" to "Vitamins",
            "iron" to "Iron", "other" to "Other medicine"),
        "move" to listOf("walk" to "Walking", "run" to "Running", "gym" to "Gym", "yoga" to "Yoga", "swim" to "Swimming", "bike" to "Cycling",
            "sport" to "Sport", "rest" to "Rest day"),
        "social" to listOf("party" to "Party", "friends" to "Friends", "alone" to "Time alone", "stress" to "Stress", "travel" to "Travel", "alcohol" to "Alcohol"),
        "peri" to listOf("hot_flash" to "Hot flashes", "night_sweat" to "Night sweats", "brain_fog" to "Brain fog", "palpitations" to "Racing heart",
            "dryness" to "Vaginal dryness", "hair" to "Hair changes"),
    )

    val FLOW = listOf(0L to "No flow", 1L to "Spotting", 2L to "Light", 3L to "Medium", 4L to "Heavy")

    /** A flow word in the cycle's own Spanish: [tr] reads the shared words first, where "Light" is the theme
     *  ("Claro"), not the flow ("Poco") (UX review U-C6). */
    fun flowLabel(label: String): String = if (Lang.spanish) ES_CYCLE[label] ?: tr(label) else label

    val MODES = listOf("track" to "Track my cycle", "conceive" to "Trying to get pregnant", "pregnant" to "Pregnant", "peri" to "Perimenopause")

    val BIRTH_CONTROL = listOf("" to "None", "pill" to "Pill", "ring" to "Ring", "patch" to "Patch", "shot" to "Shot", "iud" to "IUD", "implant" to "Implant")

    /** Every built-in tag's words in the app's language, for the CSV (UX review U-C12: names, not ids). */
    fun tagLabels(): Map<String, String> = TAGS.flatMap { (cat, l) -> l.map { "$cat.${it.first}" to tr(it.second) } }.toMap()

    /** A tag's words: built-in from the lists above, custom from the journal. */
    fun tagLabel(id: String, custom: Map<String, String>): String {
        custom[id]?.let { return it }
        val (cat, t) = id.split('.', limit = 2).let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        return TAGS[cat]?.firstOrNull { it.first == t }?.second?.let(::tr) ?: id
    }

    fun catLabel(cat: String): String = tr(CATEGORIES.firstOrNull { it.first == cat }?.second ?: cat)
}
