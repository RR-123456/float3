package com.pragon.mobile

/**
 * Turns what the speech recognizer heard into something Float mode can act on.
 * No Android classes in here on purpose, so it is easy to test.
 *
 * Control phrases work alone or with "pragon" in front:
 *   "im home" / "daddy's home" (or "pragon im home")  -> Wake
 *   "mute" (or "hey pragon mute")                      -> Mute
 *   "goodbye" (or "hey pragon goodbye")                -> Goodbye
 * Without "pragon" the sentence has to be short. Everything else only counts while Float mode is active.
 */
object VoiceParser {

    sealed class Cmd {
        object None : Cmd()
        object Wake : Cmd()
        object Mute : Cmd()
        object Goodbye : Cmd()
        /** A phone action for CommandExecutor (action + value). */
        data class Phone(val action: String, val value: String = "") : Cmd()
        /** Free-form request for the AI engine (only when the person said "pragon ..."). */
        data class Ask(val text: String) : Cmd()
    }

    // Speech recognizers often mishear the name. These are the usual suspects.
    private val WAKE = Regex(
        "(?:^| )(?:pragon|paragon|pragan|pragun|pragone|pragonn|prakon|pergon|bragon|dragon|" +
            "pre gone|pro gone|prague on|prague one|prug on|pra gone|pra gun)(?= |$)"
    )
    private val FILLER_START = Regex("^(?:(?:hey|hi|hello|ok|okay|yo)\\s+)+")

    fun norm(s: String): String = s.lowercase()
        .replace("\u2019", "").replace("'", "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun hasWake(n: String) = WAKE.containsMatchIn(n)

    /** Removes the wake word and polite filler so "hey pragon please open youtube" -> "open youtube". */
    private fun clean(n: String): String {
        var c = n
        c = WAKE.replace(c, " ")
        c = c.replace(Regex("\\s+"), " ").trim()
        c = FILLER_START.replace(c, "").trim()
        c = c.replace(Regex("^(?:(?:please|can you|could you|would you|will you|now|just)\\s+)+"), "")
        c = c.replace(Regex("(?:\\s+(?:please|now|for me))+$"), "")
        return c.trim()
    }

    private val WAKE_PHRASE = Regex("\\b(?:im|i m|i am|iam|daddy|daddys|daddy is|dady|dad|dads|dad is)\\b.*\\bhome\\b")
    private val MUTE_PHRASE = Regex("\\b(?:mute|stop listening|be quiet|go silent|go to sleep)\\b")
    private val GOODBYE_PHRASE = Regex("\\b(?:goodbye|good bye|bye bye|bye|shut down|close yourself|go away)\\b")

    /**
     * @param alts the recognizer's alternatives, best first
     * @param active true when Float mode is listening for commands
     */
    fun parse(alts: List<String>, active: Boolean): Cmd {
        val norms = alts.map { norm(it) }.filter { it.isNotEmpty() }
        if (norms.isEmpty()) return Cmd.None

        // 0. "close pragon / close yourself / close float mode" must never become "close the current app"
        val selfClose = Regex(
            "^(?:(?:hey|ok|okay)\\s+)?(?:close|quit|exit|end|stop|shut down|kill)\\s+(?:the\\s+)?" +
                "(?:pragon|paragon|pragan|pragun|dragon|yourself|float mode|float|voice mode)$"
        )
        if (norms.any { selfClose.matches(it) }) return Cmd.Goodbye

        // 1. control phrases. They work with or without "pragon". Without it the sentence must be
        //    short ("goodbye", "mute please", "i'm home"), so a long sentence from a video can't trigger them.
        fun words(n: String) = n.split(" ").size
        for (n in norms) {
            if (GOODBYE_PHRASE.containsMatchIn(n) && (hasWake(n) || words(n) <= 3)) return Cmd.Goodbye
        }
        for (n in norms) {
            if (MUTE_PHRASE.containsMatchIn(n) && (hasWake(n) || words(n) <= 4)) return Cmd.Mute
        }
        for (n in norms) {
            if (WAKE_PHRASE.containsMatchIn(n) && (hasWake(n) || words(n) <= 5)) return Cmd.Wake
        }
        if (!active) return Cmd.None

        // 2. commands: first alternative that makes sense
        for (n in norms) {
            val c = clean(n)
            if (c.isEmpty()) continue
            commandFor(c)?.let { return it }
        }
        // 3. nothing matched; if they addressed Pragon, hand it to the AI
        val first = norms.first()
        if (hasWake(first)) {
            val c = clean(first)
            if (c.length >= 3) return Cmd.Ask(c)
        }
        return Cmd.None
    }

    private fun commandFor(c: String): Cmd? {
        // ---- phone buttons ----
        if (Regex("^(?:go )?(?:to )?(?:the )?home(?: screen)?$").matches(c)) return Cmd.Phone("key", "home")
        if (Regex("^(?:go )?back$").matches(c)) return Cmd.Phone("key", "back")
        if (Regex("^(?:show |open )?(?:the )?recents?(?: apps?)?$").matches(c)) return Cmd.Phone("key", "recents")
        if (Regex("^(?:show |open |pull down )?(?:the )?notifications?(?: panel| shade)?$").matches(c))
            return Cmd.Phone("key", "notifications")
        if (Regex("^(?:open )?quick settings$").matches(c)) return Cmd.Phone("key", "quick_settings")
        if (Regex("^(?:lock|lock the|lock my)(?: phone| screen)?$|^turn off (?:the )?screen$").matches(c))
            return Cmd.Phone("key", "lock")

        // ---- volume / media ----
        if (Regex("^(?:volume up|louder|increase (?:the )?volume|raise (?:the )?volume|turn (?:it|the volume) up)$").matches(c))
            return Cmd.Phone("key", "volume_up")
        if (Regex("^(?:volume down|quieter|lower (?:the )?volume|decrease (?:the )?volume|turn (?:it|the volume) down)$").matches(c))
            return Cmd.Phone("key", "volume_down")
        if (Regex("^(?:pause|play|resume|stop|play pause)(?: the| this)?(?: video| music| song| it)?$").matches(c))
            return Cmd.Phone("key", "play_pause")
        if (Regex("^next(?: video| song| track)?$|^skip(?: this)?(?: video| song| track)?$").matches(c))
            return Cmd.Phone("key", "next")
        if (Regex("^(?:previous|prev|last)(?: video| song| track)?$|^go to previous$").matches(c))
            return Cmd.Phone("key", "previous")

        // ---- scrolling ----
        Regex("^(scroll|swipe) (up|down|left|right)$").matchEntire(c)?.let {
            val verb = it.groupValues[1]
            val dir = it.groupValues[2]
            // "scroll down" = move the content up = swipe up
            val swipe = if (verb == "scroll") when (dir) { "down" -> "up"; "up" -> "down"; else -> dir } else dir
            return Cmd.Phone("swipe", swipe)
        }

        // ---- YouTube / web search ----
        Regex("^(?:search|find|look up) (?:for )?(.+?) (?:on|in) youtube$").matchEntire(c)?.let { return Cmd.Phone("youtube_search", it.groupValues[1]) }
        Regex("^play (.+?) (?:on|in) youtube$").matchEntire(c)?.let { return Cmd.Phone("youtube_search", it.groupValues[1]) }
        Regex("^(?:search youtube|youtube search|search youtube for|youtube) (?:for )?(.+)$").matchEntire(c)?.let { return Cmd.Phone("youtube_search", it.groupValues[1]) }
        Regex("^(?:google|search google for|search for|search|look up) (.+)$").matchEntire(c)?.let { return Cmd.Phone("web_search", it.groupValues[1]) }

        // ---- close an app ("close youtube", "close this app", "close") ----
        Regex("^(?:close|quit|exit|kill|terminate|force stop|force close|shut down|shut|end)(?: (.*))?$").matchEntire(c)?.let {
            var target = it.groupValues[1].trim().removePrefix("the ").removePrefix("app ").removeSuffix(" app").trim()
            if (target == "yourself" || target == "float mode" || target == "float" || target == "pragon") return Cmd.Goodbye
            return Cmd.Phone("close_app", target)
        }

        // ---- open an app or a site ("open instagram", "open youtube.com") ----
        Regex("^(?:open|launch|start|run|go to|switch to|take me to|show me)(?: the)?(?: app)? (.+?)(?: app)?$").matchEntire(c)?.let {
            val target = it.groupValues[1].trim()
            if (target.isEmpty()) return null
            if (Regex("^[a-z0-9-]+ (?:dot )?(?:com|org|net|in|io)$").matches(target)) {
                return Cmd.Phone("open_url", target.replace(" dot ", ".").replace(" ", "."))
            }
            return Cmd.Phone("open_app", target)
        }
        return null
    }
}
