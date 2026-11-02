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
                    val attrs = attributes(body, nameEnd)
                    val el = Element(name, attrs)
                    stack.last().children += el
                    if (!selfClosing && name !in VOID) stack += el
                    i = end + 1
                }
            }
        }
        return root
    }

    /** `name="value"` pairs (also single-quoted, bare, or valueless) from [from] in a tag's body. */
    private fun attributes(body: String, from: Int): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = from
        val n = body.length
        while (i < n) {
            while (i < n && (body[i].isWhitespace() || body[i] == '/')) i++
            val nameStart = i
            while (i < n && !body[i].isWhitespace() && body[i] != '=' && body[i] != '/') i++
            if (i == nameStart) break
            val name = body.substring(nameStart, i).lowercase()
            while (i < n && body[i].isWhitespace()) i++
            var value = ""
            if (i < n && body[i] == '=') {
                i++
                while (i < n && body[i].isWhitespace()) i++
                if (i < n && (body[i] == '"' || body[i] == '\'')) {
                    val q = body[i]
                    val close = body.indexOf(q, i + 1).let { if (it < 0) n else it }
                    value = body.substring(i + 1, close)
                    i = close + 1
                } else {
                    val s = i
                    while (i < n && !body[i].isWhitespace()) i++
                    value = body.substring(s, i)
                }
            }
            out[name] = decode(value)
        }
        return out
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

    /** The ids of a file's `<section id="/scriptures/...">` blocks, without "/scriptures/". */
    fun sectionIds(html: String): List<String> {
        val open = "<section id=\"/scriptures/"
        val out = ArrayList<String>()
        var i = html.indexOf(open)
        while (i >= 0) {
            val start = i + open.length
            val end = html.indexOf('"', start)
            if (end < 0) break
            out += html.substring(start, end)
            i = html.indexOf(open, end)
        }
        return out
    }

    /**
     * Splits a big file into its `<section id="...">` blocks without parsing the whole thing: each value
     * is the section's raw HTML, from its start tag to just before the next top-level section start.
     * Only sections whose id starts with "/" (the Gospel Library uris) count, so nested id-less
     * sections stay inside their parent.
     */
    fun sections(html: String): LinkedHashMap<String, String> {
        val open = "<section id=\"/"
        val starts = ArrayList<Pair<Int, String>>()
        var i = html.indexOf(open)
        while (i >= 0) {
            val idStart = i + open.length - 1
            val idEnd = html.indexOf('"', idStart)
            if (idEnd < 0) break
            if (html.startsWith("\">", idEnd)) starts += i to html.substring(idStart, idEnd)
            i = html.indexOf(open, idEnd)
        }
        val out = LinkedHashMap<String, String>()
        starts.forEachIndexed { k, (start, id) ->
            val end = starts.getOrNull(k + 1)?.first ?: html.length
            out[id] = html.substring(start, end)
        }
        return out
    }
}
