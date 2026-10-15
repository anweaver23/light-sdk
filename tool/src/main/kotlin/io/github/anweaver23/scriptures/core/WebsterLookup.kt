package io.github.anweaver23.scriptures.core

/**
 * Pure lookup logic for the bundled Webster's 1828 shards (`assets/dictionary/`, built by
 * `scripts/build_webster_assets.py`). No Android and no I/O: callers supply the shard text.
 *
 * Shard format, one printed entry per line:
 * `key TAB HEADWORD TAB part-of-speech TAB sense TAB sense ...`. A key with several entries (noun
 * and verb, say) has several lines. A two-field line, `alias TAB key`, redirects a closed compound
 * to Webster's hyphenated headword ("firstborn" -> "first-born"); the target may be in another shard.
 * `index.txt` lists the two-letter prefixes that are split into three-letter shards. [shardFor] must
 * match `shard_for` in the generator.
 */
object WebsterLookup {
    const val ASSET_DIR = "dictionary"
    const val INDEX_PATH = "$ASSET_DIR/index.txt"

    /** The split prefixes listed in `index.txt`. */
    fun parseSplitIndex(text: String): Set<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Shard name for a normalized [key]: "charity" -> "ch", or "cha" when "ch" is split; "a" -> "a_". */
    fun shardFor(key: String, split: Set<String>): String {
        fun prefix(n: Int) = buildString {
            for (i in 0 until n) {
                val c = key.getOrNull(i)
                append(if (c != null && c in 'a'..'z') c else '_')
            }
        }
        val p2 = prefix(2)
        return if (p2 in split) prefix(3) else p2
    }

    /** Asset path of a shard. The prefix keeps reserved Windows names ("con", "nul") out of the repo. */
    fun shardPath(shard: String): String = "$ASSET_DIR/shard-$shard.txt"

    /** One parsed shard file. */
    class Shard(val entries: Map<String, List<Definition>>, val aliases: Map<String, String>) {
        companion object {
            val EMPTY = Shard(emptyMap(), emptyMap())
        }
    }

    /** Entries by key, in source order, plus the shard's compound aliases. */
    fun parseShard(text: String): Shard {
        val entries = HashMap<String, MutableList<Definition>>()
        val aliases = HashMap<String, String>()
        for (line in text.lineSequence()) {
            if (line.isEmpty()) continue
            val f = line.split('\t')
            when {
                f.size == 2 -> aliases[f[0]] = f[1]
                f.size >= 4 -> entries.getOrPut(f[0]) { ArrayList(1) } += Definition(
                    headword = f[1],
                    partOfSpeech = f[2].ifEmpty { null },
                    senses = f.subList(3, f.size).toList(),
                )
            }
        }
        return Shard(entries, aliases)
    }

    /** Entries stored under one normalized [key], following an alias. [shard] loads a shard by name. */
    fun entriesFor(key: String, split: Set<String>, shard: (String) -> Shard): List<Definition> {
        val s = shard(shardFor(key, split))
        s.entries[key]?.let { return it }
        val target = s.aliases[key] ?: return emptyList()
        return shard(shardFor(target, split)).entries[target].orEmpty()
    }

    /**
     * Entries for [word], best match first. [entriesFor] returns the entries stored under one
     * normalized key. The exact form wins; an archaic irregular form ("spake") also brings its
     * base ("speak"). Otherwise the first hit among [fallbacks] is used: Webster's spelling
     * ("honour" -> "honor") and regular inflections ("giveth" -> "give", "cried" -> "cry").
     */
    fun define(word: String, entriesFor: (String) -> List<Definition>): List<Definition> {
        val key = normalize(word)
        if (key.isEmpty()) return emptyList()
        val out = ArrayList<Definition>()
        out += entriesFor(key)
        IRREGULAR[key]?.let { base -> out += entriesFor(base) }
        if (out.isNotEmpty()) return out.distinct()
        for (candidate in fallbacks(key)) {
            val found = entriesFor(candidate)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /**
     * The key a word is stored under: lowercase, straight apostrophes, no possessive.
     * "Charity" -> "charity", "man’s" -> "man", "brethren's" -> "brethren", "fathers'" -> "fathers".
     */
    fun normalize(word: String): String {
        var w = word.trim().lowercase().replace('’', '\'').replace('‘', '\'')
        w = w.trim('\'', '-', '.', ',', ';', ':', '!', '?', '"', '(', ')')
        if (w.endsWith("'s")) w = w.dropLast(2) else if (w.endsWith("s'")) w = w.dropLast(1)
        return w.trim('\'')
    }

    /** Spelling variants and base forms to try, in order, when [key] itself isn't in the dictionary. */
    fun fallbacks(key: String): List<String> {
        val out = LinkedHashSet<String>()
        out += inflectionBases(key)
        for (variant in spellingVariants(key)) {
            out += variant
            out += inflectionBases(variant)
        }
        out.remove(key)
        return out.toList()
    }

    /**
     * The KJV and Book of Mormon keep British and older spellings that Webster's American
     * dictionary changed: honour/honor, sepulchre/sepulcher, offence/offense, marvelled/marveled,
     * fulness/fullness, fulfil/fulfill, musick/music, carcase/carcass. Each rule alone, then all together.
     */
    fun spellingVariants(key: String): List<String> {
        val out = LinkedHashSet<String>()
        for (rule in SPELLING_RULES) out += rule(key)
        out += SPELLING_RULES.fold(key) { acc, rule -> rule(acc) }
        out.remove(key)
        return out.toList()
    }

    private val SPELLING_RULES: List<(String) -> String> = listOf(
        { it.replace("our", "or") },
        { it.replace(Regex("([^aeiou])re([sd]?)$"), "$1er$2") },
        { it.replace(Regex("ence([sd]?)$"), "ense$1") },
        { it.replace(Regex("([aeiou])ll(ed|ing|er|ers|or|ors|ous)$"), "$1l$2") },
        { it.replace(Regex("ful(ness|ly)?$"), "full$1") },
        { it.replace(Regex("([^il])il(s|ment)?$"), "$1ill$2") }, // fulfil, distil
        { it.replace(Regex("([^l])lful"), "$1llful") }, // skilful, wilfully
        { it.replace(Regex("([aeiou])ll(en)$"), "$1l$2") }, // woollen
        { it.replace(Regex("([aeiou])pp(ed|ing|er|ers)$"), "$1p$2") }, // worshippers
        { it.replace(Regex("ise([sd]?)$"), "ice$1") }, // practise
        { it.replace(Regex("ick$"), "ic") },
        { it.replace(Regex("cases$"), "casses").replace(Regex("case$"), "cass") }, // carcase
        { it.replace(Regex("^axe(s?)$"), "ax$1") },
    )

    /**
     * Possible base forms of a normalized word, most likely first. Not checked against the
     * dictionary; [define] takes the first that exists.
     */
    fun inflectionBases(key: String): List<String> {
        val out = LinkedHashSet<String>()
        // "know'st" -> "knowest"
        if (key.endsWith("'st")) out += inflectionBases(key.dropLast(3) + "est")
        if ('-' in key) out += key.replace("-", "")

        fun stem(stem: String) {
            if (stem.length < 2) return
            // "hop|ed" reads as "hope" more often than "hop"; "walk|ed" is just "walk".
            val wantsE = Regex("[^aeiou][aeiou][^aeiouwxy]$").containsMatchIn(stem)
            val withE = stem + "e"
            if (wantsE) out += withE
            out += stem
            if (!wantsE) out += withE
            // "begg|ing" -> "beg", "dwell|eth" stays "dwell" above
            if (stem.length >= 3 && stem.last() == stem[stem.length - 2] && stem.last() !in "aeiousl") {
                out += stem.dropLast(1)
            }
        }

        when {
            key.endsWith("ieth") || key.endsWith("iest") -> {
                out += key.dropLast(4) + "y" // crieth, holiest
                stem(key.dropLast(3))
            }
            key.endsWith("eth") || key.endsWith("est") -> stem(key.dropLast(3))
            key.endsWith("ied") -> {
                out += key.dropLast(3) + "y"
                stem(key.dropLast(2))
            }
            key.endsWith("ying") -> {
                out += key.dropLast(4) + "ie" // dying
                stem(key.dropLast(3))
            }
            key.endsWith("ing") -> stem(key.dropLast(3))
            key.endsWith("ed") -> stem(key.dropLast(2))
            key.endsWith("ves") -> {
                out += key.dropLast(3) + "f" // wolves, yourselves
                out += key.dropLast(3) + "fe" // wives
                out += key.dropLast(1)
            }
            key.endsWith("men") -> out += key.dropLast(3) + "man" // horsemen, watchmen
            key.endsWith("ies") -> {
                out += key.dropLast(3) + "y"
                out += key.dropLast(1)
            }
            key.endsWith("es") -> {
                out += key.dropLast(1)
                out += key.dropLast(2)
            }
            key.endsWith("st") -> {
                // hadst, didst, couldst, knowst
                out += key.dropLast(2)
                out += key.dropLast(3)
            }
            key.endsWith("ier") -> out += key.dropLast(3) + "y"
            key.endsWith("er") -> stem(key.dropLast(2))
        }
        if (key.endsWith("s") && !key.endsWith("ss")) out += key.dropLast(1)
        out.remove(key)
        return out.filter { it.length >= 2 }
    }

    /**
     * Archaic and irregular forms common in the KJV and the Book of Mormon, mapped to the word
     * Webster defines. Looked up alongside the exact form, since many of these are also words in
     * their own right ("art", "bare", "slew").
     */
    val IRREGULAR: Map<String, String> = mapOf(
        "hath" to "have", "hast" to "have", "hadst" to "have", "hadest" to "have",
        "doth" to "do", "dost" to "do", "doeth" to "do", "doest" to "do", "didst" to "do", "didest" to "do",
        "saith" to "say", "sayest" to "say", "sayeth" to "say", "saidst" to "say",
        "spake" to "speak", "spakest" to "speak", "wrought" to "work", "wroughtest" to "work",
        "art" to "be", "wast" to "be", "wert" to "be", "beest" to "be",
        "shalt" to "shall", "wilt" to "will", "canst" to "can", "couldest" to "could", "couldst" to "could",
        "wouldest" to "would", "wouldst" to "would", "shouldest" to "should", "shouldst" to "should",
        "mayest" to "may", "mayst" to "may", "mightest" to "might", "mightst" to "might",
        "brake" to "break", "bare" to "bear", "barest" to "bear", "clave" to "cleave", "drave" to "drive",
        "gat" to "get", "gotten" to "get", "begat" to "beget", "begotten" to "beget", "sware" to "swear",
        "slew" to "slay", "slewest" to "slay", "slain" to "slay", "shew" to "show", "shewed" to "show",
        "sheweth" to "show", "shewn" to "show", "digged" to "dig", "holpen" to "help", "durst" to "dare",
        "trode" to "tread", "bade" to "bid", "forbade" to "forbid", "gave" to "give", "gavest" to "give",
        "came" to "come", "camest" to "come", "went" to "go", "wentest" to "go", "saw" to "see",
        "sawest" to "see", "seen" to "see", "ran" to "run", "ate" to "eat", "sat" to "sit", "sang" to "sing",
        "stood" to "stand", "taken" to "take", "took" to "take", "tookest" to "take", "spoke" to "speak",
        "spoken" to "speak", "knew" to "know", "knewest" to "know", "known" to "know", "wist" to "wit",
        "wot" to "wit", "wotteth" to "wit", "brought" to "bring", "broughtest" to "bring",
        "thought" to "think", "thoughtest" to "think", "taught" to "teach", "sought" to "seek",
        "bought" to "buy", "caught" to "catch", "fought" to "fight", "built" to "build", "dwelt" to "dwell",
        "fled" to "flee", "fell" to "fall", "fallen" to "fall", "rose" to "rise", "risen" to "rise",
        "arose" to "arise", "arisen" to "arise", "smote" to "smite", "smitten" to "smite", "wrote" to "write",
        "written" to "write", "chose" to "choose", "chosen" to "choose", "drank" to "drink",
        "drunken" to "drink", "began" to "begin", "begun" to "begin", "rent" to "rend", "lent" to "lend",
        "sent" to "send", "spent" to "spend", "left" to "leave", "led" to "lead", "fed" to "feed",
        "bled" to "bleed", "laid" to "lay", "lain" to "lie", "slept" to "sleep",
        "wept" to "weep", "kept" to "keep", "spat" to "spit", "strove" to "strive", "striven" to "strive",
        "forsook" to "forsake", "forsaken" to "forsake", "forgave" to "forgive", "forgiven" to "forgive",
        "hid" to "hide", "hidden" to "hide", "bore" to "bear", "borne" to "bear", "born" to "bear",
        "tare" to "tear", "torn" to "tear", "ware" to "wear", "worn" to "wear", "wove" to "weave",
        "woven" to "weave", "shook" to "shake", "shaken" to "shake", "ye" to "you", "thee" to "thou",
        "thy" to "thou", "thine" to "thou", "men" to "man", "women" to "woman", "children" to "child",
        "brethren" to "brother", "feet" to "foot", "teeth" to "tooth", "oxen" to "ox", "kine" to "cow",
        "wives" to "wife", "lives" to "life", "knives" to "knife", "loaves" to "loaf", "leaves" to "leaf",
        "sheaves" to "sheaf", "thieves" to "thief", "mice" to "mouse", "lice" to "louse", "geese" to "goose",
        "dice" to "die", "staves" to "staff", "hoofs" to "hoof", "cherubims" to "cherub",
        "seraphims" to "seraph", "cherubim" to "cherub", "seraphim" to "seraph",
        "drew" to "draw", "drewest" to "draw", "withdrew" to "withdraw", "dealt" to "deal",
        "graven" to "grave", "forbidden" to "forbid", "bidden" to "bid", "overthrew" to "overthrow",
        "overthrown" to "overthrow", "withstood" to "withstand", "holden" to "hold", "overcame" to "overcome",
        "forbare" to "forbear", "forgat" to "forget", "foretold" to "foretell", "knelt" to "kneel",
        "befallen" to "befall", "befell" to "befall", "shaven" to "shave", "overtaken" to "overtake",
        "overtook" to "overtake", "pled" to "plead", "spue" to "spew", "rebuilt" to "rebuild",
        "overflown" to "overflow", "sycomore" to "sycamore", "caterpiller" to "caterpillar",
        "caterpillers" to "caterpillar", "understood" to "understand", "undertook" to "undertake",
        "mistook" to "mistake", "awoke" to "awake", "beheld" to "behold", "upheld" to "uphold",
        "withheld" to "withhold", "slid" to "slide", "stank" to "stink", "stunk" to "stink", "swam" to "swim",
        "flew" to "fly", "flown" to "fly", "grew" to "grow", "grown" to "grow", "threw" to "throw",
        "thrown" to "throw", "blew" to "blow", "blown" to "blow", "hewn" to "hew", "sown" to "sow",
        "mown" to "mow", "strewed" to "strew", "sodden" to "seethe", "sod" to "seethe",
    )
}
