package io.github.anweaver23.scriptures.core.importer

/**
 * A small, lenient HTML/XHTML reader for the Church's scripture files (the Victor Reader HTML and the
 * EPUB chapters). Not a general HTML parser: it knows void elements, decodes the entities these files
 * use, and recovers from a stray end tag by closing back to the nearest open element of that name.
 * No Android here, so it runs in unit tests.
 */
sealed interface Node

class Element(val name: String, val attrs: Map<String, String>, val children: MutableList<Node> = ArrayList()) : Node {
    val classes: List<String> get() = attrs["class"]?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()
    fun hasClass(c: String) = c in classes
    val id: String? get() = attrs["id"]

    /** All text inside, entities decoded, as written (no whitespace folding). */
    fun text(): String = buildString { appendText(this@Element, this) }

    /** Child elements only. */
    val elements: List<Element> get() = children.filterIsInstance<Element>()

    /** Depth-first, this element included. */
    fun walk(): Sequence<Element> = sequence {
        yield(this@Element)
        for (c in children) if (c is Element) yieldAll(c.walk())
    }

    fun first(predicate: (Element) -> Boolean): Element? = walk().firstOrNull(predicate)
    fun all(predicate: (Element) -> Boolean): List<Element> = walk().filter(predicate).toList()
    fun firstByClass(c: String) = first { it.hasClass(c) }
    fun allByClass(c: String) = all { it.hasClass(c) }

    override fun toString() = "<$name ${attrs}>"
}

class TextNode(val text: String) : Node

private fun appendText(node: Node, out: StringBuilder) {
    when (node) {
        is TextNode -> out.append(node.text)
        is Element -> if (node.name == "br") out.append('\n') else node.children.forEach { appendText(it, out) }
    }
}

object Html {
    private val VOID = setOf("br", "img", "meta", "link", "hr", "input", "col", "area", "base", "wbr", "source")
    private val ATTR = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*(?:=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?""")

    /** Parses [html] into a synthetic root element named "#root". */
    fun parse(html: String): Element {
        val root = Element("#root", emptyMap())
        val stack = ArrayList<Element>().apply { add(root) }
        var i = 0
        val n = html.length
        while (i < n) {
            val lt = html.indexOf('<', i)
            val textEnd = if (lt < 0) n else lt
            if (textEnd > i) stack.last().children += TextNode(decode(html.substring(i, textEnd)))
            if (lt < 0) break
            when {
                html.startsWith("<!--", lt) -> {
                    val end = html.indexOf("-->", lt + 4)
                    i = if (end < 0) n else end + 3
                }
                html.startsWith("<!", lt) || html.startsWith("<?", lt) -> {
                    val end = html.indexOf('>', lt)
                    i = if (end < 0) n else end + 1
                }
                html.startsWith("</", lt) -> {
                    val end = html.indexOf('>', lt)
                    val name = html.substring(lt + 2, if (end < 0) n else end).trim().lowercase()
                    val at = stack.indexOfLast { it.name == name }
                    if (at > 0) while (stack.size > at) stack.removeAt(stack.lastIndex)
                    i = if (end < 0) n else end + 1
                }
                else -> {
                    val end = tagEnd(html, lt)
                    var body = html.substring(lt + 1, end)
                    val selfClosing = body.endsWith("/")
                    if (selfClosing) body = body.dropLast(1)
                    val nameEnd = body.indexOfFirst { it.isWhitespace() }.let { if (it < 0) body.length else it }
                    val name = body.substring(0, nameEnd).lowercase()
                    val attrs = LinkedHashMap<String, String>()
                    for (m in ATTR.findAll(body.substring(nameEnd))) {
                        val v = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: ""
                        attrs[m.groupValues[1].lowercase()] = decode(v)
                    }
                    val el = Element(name, attrs)
                    stack.last().children += el
                    if (!selfClosing && name !in VOID) stack += el
                    i = end + 1
                }
            }
        }
        return root
    }

    /** The '>' closing the tag opened at [lt], skipping any inside quoted attribute values. */
    private fun tagEnd(html: String, lt: Int): Int {
        var quote: Char? = null
        var i = lt + 1
        while (i < html.length) {
            val c = html[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return i
            }
            i++
        }
        return html.length - 1
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘",
        "rdquo" to "”", "ldquo" to "“", "para" to "¶", "sect" to "§", "middot" to "·",
        "thinsp" to " ", "ensp" to " ", "emsp" to " ", "copy" to "©", "shy" to "",
    )

    fun decode(s: String): String {
        if ('&' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i)
                if (semi in (i + 2)..(i + 10)) {
                    val ent = s.substring(i + 1, semi)
                    val decoded = when {
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                        else -> NAMED[ent]
                    }
                    if (decoded != null) {
                        out.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /**
     * Splits a big file into its `<section id="...">` blocks without parsing the whole thing: each value
     * is the section's raw HTML, from its start tag to just before the next top-level section start.
     * Only sections whose id starts with "/" (the Gospel Library uris) count, so nested id-less
     * sections stay inside their parent.
     */
    fun sections(html: String): LinkedHashMap<String, String> {
        val marker = Regex("""<section id="(/[^"]*)">""")
        val starts = marker.findAll(html).toList()
        val out = LinkedHashMap<String, String>()
        starts.forEachIndexed { k, m ->
            val end = starts.getOrNull(k + 1)?.range?.first ?: html.length
            out[m.groupValues[1]] = html.substring(m.range.first, end)
        }
        return out
    }
}
