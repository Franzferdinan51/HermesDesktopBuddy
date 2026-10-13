package com.duckets.hermesbuddy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GadgetProtocolTest {

    @Test
    fun `parse welcome`() {
        val m = parseInMsg(
            JSONObject("""{"type":"welcome","session":"s1","paired":true,
                "heartbeat_s":30,"server":"hermes"}""")
        ) as InMsg.Welcome
        assertEquals("s1", m.session)
        assertEquals(true, m.paired)
        assertEquals(30, m.heartbeatS)
        assertEquals("hermes", m.server)
    }

    @Test
    fun `parse pairing and paired`() {
        val p = parseInMsg(
            JSONObject("""{"type":"pairing","code":"ABCDEF","command":"hermes pair ABCDEF"}""")
        ) as InMsg.Pairing
        assertEquals("ABCDEF", p.code)
        assertEquals("hermes pair ABCDEF", p.command)
        assertTrue(parseInMsg(JSONObject("""{"type":"paired"}""")) is InMsg.Paired)
        assertTrue(parseInMsg(JSONObject("""{"type":"unpaired"}""")) is InMsg.Unpaired)
    }

    @Test
    fun `parse reply delta and final`() {
        val d = parseInMsg(
            JSONObject("""{"type":"reply.delta","turn":"t1","text":"Hel"}""")
        ) as InMsg.ReplyDelta
        assertEquals("t1", d.turn)
        assertEquals("Hel", d.text)
        val r = parseInMsg(
            JSONObject("""{"type":"reply","turn":"t1","text":"Hello","interim":false}""")
        ) as InMsg.Reply
        assertEquals(false, r.interim)
    }

    @Test
    fun `parse prompt action audio and turn events`() {
        val p = parseInMsg(
            JSONObject("""{"type":"prompt","id":"p1","title":"T","text":"X","ttl_s":60}""")
        ) as InMsg.Prompt
        assertEquals("p1", p.id)
        val a = parseInMsg(
            JSONObject("""{"type":"action","id":"a1","name":"buddy.vibrate","args":{}}""")
        ) as InMsg.Action
        assertEquals("buddy.vibrate", a.name)
        val s = parseInMsg(
            JSONObject("""{"type":"audio.start","stream":3,"rate":16000,"turn":"t1"}""")
        ) as InMsg.AudioStart
        assertEquals(3, s.stream)
        assertEquals(16000, s.rate)
        assertTrue(parseInMsg(JSONObject("""{"type":"audio.end","stream":3}""")) is InMsg.AudioEnd)
        assertTrue(parseInMsg(JSONObject("""{"type":"turn.start","turn":"t1"}""")) is InMsg.TurnStart)
        val e = parseInMsg(
            JSONObject("""{"type":"turn.end","turn":"t1","outcome":"success"}""")
        ) as InMsg.TurnEnd
        assertEquals("success", e.outcome)
    }

    @Test
    fun `parse error and unknown`() {
        val e = parseInMsg(
            JSONObject("""{"type":"error","code":"denied","message":"no"}""")
        ) as InMsg.Error
        assertEquals("denied", e.code)
        val u = parseInMsg(JSONObject("""{"type":"future.thing","x":1}""")) as InMsg.Unknown
        assertEquals("future.thing", u.type)
    }

    @Test
    fun `binary frame round trip`() {
        val payload = ByteArray(640) { (it % 251).toByte() }
        val encoded = BinaryFrame.encode(
            channel = Proto.CH_AUDIO, stream = 7, seq = 1234, payload = payload
        )
        val decoded = BinaryFrame.decode(encoded.toByteArray())
        assertEquals(Proto.CH_AUDIO, decoded!!.channel)
        assertEquals(7, decoded.stream)
        assertEquals(1234, decoded.seq)
        assertTrue(payload.contentEquals(decoded.payload))
    }

    @Test
    fun `binary frame rejects truncated and empty`() {
        assertNull(BinaryFrame.decode(ByteArray(3)))
        assertNull(BinaryFrame.decode(ByteArray(0)))
        // Header-only frame is legal (empty payload).
        val empty = BinaryFrame.decode(
            BinaryFrame.encode(Proto.CH_AUDIO, 1, 2, ByteArray(0)).toByteArray()
        )
        assertEquals(1, empty!!.stream)
        assertEquals(0, empty.payload.size)
    }

    @Test
    fun `outgoing builders shape`() {
        assertEquals("session.new", OutMsg.sessionNew().getString("type"))
        assertEquals("cancel", OutMsg.cancel().getString("type"))
        val t = OutMsg.text("t1", "hi")
        assertEquals("text", t.getString("type"))
        assertEquals("t1", t.getString("id"))
        assertEquals("hi", t.getString("text"))
        val pr = OutMsg.promptReply("p1", "yes")
        assertEquals("yes", pr.getString("answer"))
        val ar = OutMsg.actionResult("a1", true, JSONObject().put("k", 1), null)
        assertEquals(true, ar.getBoolean("ok"))
        assertEquals(1, ar.getJSONObject("result").getInt("k"))
        val err = OutMsg.actionResult("a1", false, null, "bad")
        assertEquals("bad", err.getString("error"))
        val start = OutMsg.audioStart("u1", 5, 16000)
        assertEquals("audio.start", start.getString("type"))
        assertEquals(16000, start.getInt("rate"))
        val end = OutMsg.audioEnd("u1", 5, 1500)
        assertEquals(1500, end.getLong("duration_ms"))
        val st = OutMsg.state(JSONObject().put("battery_pct", 99))
        assertEquals(99, st.getJSONObject("sensors").getInt("battery_pct"))
    }

    @Test
    fun `hello builder shape`() {
        val h = OutMsg.hello("hg-abc", "Phone", 16000, JSONObject())
        assertEquals("hello", h.getString("type"))
        assertEquals("hg-abc", h.getString("device_id"))
        assertEquals("Phone", h.getString("name"))
        assertEquals("android", h.getString("board"))
        assertEquals(16000, h.getJSONObject("caps").getJSONObject("mic").getInt("rate"))
        val actions = h.getJSONArray("actions")
        assertEquals(3, actions.length())
        val names = (0 until actions.length())
            .map { actions.getJSONObject(it).getString("name") }
        assertEquals(listOf("buddy.vibrate", "buddy.notify", "buddy.battery"), names)
        val auth = OutMsg.authKey("key!")
        assertEquals("key!", auth.getString("key"))
        assertEquals("m", OutMsg.authMac("m").getString("mac"))
    }
}
