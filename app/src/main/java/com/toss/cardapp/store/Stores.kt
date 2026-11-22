package com.toss.cardapp.store

import android.content.Context
import android.content.SharedPreferences
import com.toss.cardapp.card.CardKind
import com.toss.cardapp.card.CardRead
import com.toss.cardapp.card.EmvField
import com.toss.cardapp.core.MatchField
import com.toss.cardapp.core.Member
import com.toss.cardapp.core.Rule
import com.toss.cardapp.core.RuleAction
import com.toss.cardapp.core.Screen
import com.toss.cardapp.core.Trigger
import com.toss.cardapp.core.Verdict
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class LogEntry(
    val id: Long,
    val read: CardRead,
    val verdict: Verdict,
    val memberName: String?,
    val ruleName: String?,
)

private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null

object Codec {
    fun read(r: CardRead) = JSONObject().apply {
        put("time", r.time); put("kind", r.kind.name)
        put("pan", r.pan); put("expiry", r.expiry); put("holder", r.holder); put("serviceCode", r.serviceCode)
        put("atr", r.atr); put("aid", r.aid); put("appLabel", r.appLabel)
        put("track1", r.track1); put("track2", r.track2); put("track3", r.track3); put("note", r.note)
        put("emv", JSONArray(r.emvFields.map { JSONObject().put("tag", it.tag).put("name", it.name).put("value", it.value) }))
    }

    fun read(o: JSONObject): CardRead {
        val emv = o.optJSONArray("emv") ?: JSONArray()
        return CardRead(
            time = o.getLong("time"), kind = CardKind.valueOf(o.getString("kind")),
            pan = o.str("pan"), expiry = o.str("expiry"), holder = o.str("holder"), serviceCode = o.str("serviceCode"),
            atr = o.str("atr"), aid = o.str("aid"), appLabel = o.str("appLabel"),
            track1 = o.str("track1"), track2 = o.str("track2"), track3 = o.str("track3"), note = o.str("note"),
            emvFields = (0 until emv.length()).map { emv.getJSONObject(it).let { f -> EmvField(f.getString("tag"), f.getString("name"), f.getString("value")) } },
        )
    }

    fun log(e: LogEntry) = JSONObject().put("id", e.id).put("read", read(e.read)).put("verdict", e.verdict.name)
        .put("member", e.memberName).put("rule", e.ruleName)

    fun log(o: JSONObject) = LogEntry(o.getLong("id"), read(o.getJSONObject("read")), Verdict.valueOf(o.getString("verdict")), o.str("member"), o.str("rule"))

    fun member(m: Member) = JSONObject().put("id", m.id).put("name", m.name).put("field", m.field.name).put("value", m.value).put("allowed", m.allowed)

    fun member(o: JSONObject) = Member(o.getLong("id"), o.getString("name"), MatchField.valueOf(o.getString("field")), o.getString("value"), o.getBoolean("allowed"))

    fun rule(r: Rule) = JSONObject().put("id", r.id).put("name", r.name).put("trigger", r.trigger.name).put("memberId", r.memberId)
        .put("enabled", r.enabled).apply {
            when (val a = r.action) {
                is RuleAction.OpenScreen -> put("action", "screen").put("target", a.screen.name)
                is RuleAction.OpenApp -> put("action", "app").put("target", a.target)
            }
        }

    fun rule(o: JSONObject) = Rule(
        id = o.getLong("id"), name = o.getString("name"), trigger = Trigger.valueOf(o.getString("trigger")),
        memberId = if (o.isNull("memberId") || !o.has("memberId")) null else o.getLong("memberId"),
        action = if (o.getString("action") == "screen") RuleAction.OpenScreen(Screen.valueOf(o.getString("target"))) else RuleAction.OpenApp(o.getString("target")),
        enabled = o.optBoolean("enabled", true),
    )
}

private class JsonListFile<T>(context: Context, name: String, private val enc: (T) -> JSONObject, private val dec: (JSONObject) -> T) {
    private val file = SecureFile(context, name)

    fun load(): MutableList<T> {
        val arr = JSONArray(file.read() ?: return mutableListOf())
        return (0 until arr.length()).mapTo(ArrayList()) { dec(arr.getJSONObject(it)) }
    }

    fun save(items: List<T>) = file.write(JSONArray(items.map(enc)).toString())
}

class AppStore private constructor(context: Context) {
    private val logFile = JsonListFile(context, "log.enc", Codec::log, Codec::log)
    private val memberFile = JsonListFile(context, "members.enc", Codec::member, Codec::member)
    private val ruleFile = JsonListFile(context, "rules.enc", Codec::rule, Codec::rule)
    private val logs = logFile.load()
    private val members = memberFile.load()
    private val rules = ruleFile.load()

    @Synchronized fun logs(): List<LogEntry> = logs.toList()
    @Synchronized fun members(): List<Member> = members.toList()
    @Synchronized fun rules(): List<Rule> = rules.toList()

    @Synchronized fun appendLog(e: LogEntry) {
        logs.add(0, e)
        while (logs.size > MAX_LOG) logs.removeAt(logs.size - 1)
        logFile.save(logs)
    }

    @Synchronized fun clearLogs() {
        logs.clear()
        logFile.save(logs)
    }

    @Synchronized fun upsertMember(m: Member) {
        members.removeAll { it.id == m.id }
        members.add(m)
        memberFile.save(members)
    }

    @Synchronized fun deleteMember(id: Long) {
        members.removeAll { it.id == id }
        memberFile.save(members)
    }

    @Synchronized fun upsertRule(r: Rule) {
        rules.removeAll { it.id == r.id }
        rules.add(r)
        ruleFile.save(rules)
    }

    @Synchronized fun deleteRule(id: Long) {
        rules.removeAll { it.id == id }
        ruleFile.save(rules)
    }

    companion object {
        const val MAX_LOG = 10_000
        @Volatile private var instance: AppStore? = null
        fun get(context: Context): AppStore =
            instance ?: synchronized(this) { instance ?: AppStore(context.applicationContext).also { instance = it } }

        fun newId(): Long = System.currentTimeMillis() * 1000 + (0..999).random()
    }
}

object CsvExport {
    private val header = listOf("time", "type", "verdict", "member", "rule", "pan", "expiry", "holder", "service_code",
        "aid", "app_label", "atr", "track1", "track2", "track3", "emv_tags", "note")

    private fun cell(v: String?): String {
        val s = v ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun render(entries: List<LogEntry>): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder(header.joinToString(",")).append('\n')
        for (e in entries) {
            val r = e.read
            sb.append(listOf(fmt.format(Date(r.time)), r.kind.name, e.verdict.name, e.memberName, e.ruleName, r.pan, r.expiry,
                r.holder, r.serviceCode, r.aid, r.appLabel, r.atr, r.track1, r.track2, r.track3,
                r.emvFields.joinToString(";") { "${it.tag}=${it.value}" }, r.note).joinToString(",") { cell(it) }).append('\n')
        }
        return sb.toString()
    }
}

class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var maskPan: Boolean
        get() = prefs.getBoolean("mask_pan", false)
        set(v) = prefs.edit().putBoolean("mask_pan", v).apply()

    var matchField: MatchField
        get() = MatchField.valueOf(prefs.getString("match_field", MatchField.PAN.name)!!)
        set(v) = prefs.edit().putString("match_field", v.name).apply()

    var autoLaunch: Boolean
        get() = prefs.getBoolean("auto_launch", true)
        set(v) = prefs.edit().putBoolean("auto_launch", v).apply()

    val hasAdminPin: Boolean get() = prefs.contains("pin_hash")

    // PBKDF2 so a copied prefs file does not reveal a short PIN directly.
    fun setAdminPin(pin: String) {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        prefs.edit().putString("pin_salt", salt.toB64()).putString("pin_hash", hash(pin, salt).toB64()).apply()
    }

    fun checkAdminPin(pin: String): Boolean {
        val salt = prefs.getString("pin_salt", null)?.fromB64() ?: return false
        val expected = prefs.getString("pin_hash", null)?.fromB64() ?: return false
        return java.security.MessageDigest.isEqual(hash(pin, salt), expected)
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(PBEKeySpec(pin.toCharArray(), salt, 20_000, 256)).encoded

    private fun ByteArray.toB64() = android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
    private fun String.fromB64() = android.util.Base64.decode(this, android.util.Base64.NO_WRAP)
}
