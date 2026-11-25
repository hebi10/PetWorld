package com.haebi.petworld.data.account

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeletionProtocolTransportTest {
    private val job = "a".repeat(64)
    private val endpoint = requireNotNull(DeletionEndpoint.fromVerifiedOrigin("https://deletion.example.test", true))
    private val state = DeletionRequestState.new("protocol-fixture")
    private fun response(code: Int, id: String = job, phase: String = "LOCKED") =
        DeletionWireResponse(code,"application/json; charset=utf-8","no-store, private, max-age=0",
            JSONObject().put("jobId",id).put("phase",phase).toString())

    @Test fun onlyReviewedHttpsOriginWithFixedRoutes() {
        assertNull(DeletionEndpoint.fromVerifiedOrigin("https://deletion.example.test", false))
        for (bad in listOf("http://deletion.example.test", "https://localhost", "https://127.0.0.1",
            "https://a.local", "https://a.example.test:8080", "https://a.example.test/x",
            "https://a.example.test/?x=1", "https://user@a.example.test", "https://a.example.test/#x")) {
            assertNull(DeletionEndpoint.fromVerifiedOrigin(bad,true))
        }
        assertEquals("/account-deletion/request", endpoint.url(DeletionRoute.REQUEST).path)
        assertEquals("/account-deletion/status", endpoint.url(DeletionRoute.STATUS).path)
    }

    @Test fun exactRequestAndStatusContractAndRedactedObjects() = runBlocking {
        val requests = mutableListOf<DeletionWireRequest>()
        val transport = DeletionProtocolTransport(endpoint,DeletionAppCheck { "fixture-app-check" },DeletionWire { r ->
            requests += r
            response(r.route.responseCode, phase=if(r.route==DeletionRoute.REQUEST) "LOCKED" else "COMPLETE")
        })
        assertEquals(AccountDeletionPhase.LOCKED, transport.request("fixture-id-token",state.statusTicket).phase)
        assertEquals(AccountDeletionPhase.COMPLETE, transport.status(job,state.statusTicket).phase)
        val (first,second)=requests
        val body=JSONObject(first.body)
        assertEquals(setOf("confirmation","statusTicket"),body.keys().asSequence().toSet())
        assertEquals("DELETE_MY_PETWORLD_ACCOUNT",body.getString("confirmation"))
        assertEquals(state.statusTicket,body.getString("statusTicket"))
        assertEquals("Bearer fixture-id-token",first.authorization)
        assertEquals(setOf("jobId"),JSONObject(second.body).keys().asSequence().toSet())
        assertEquals("Bearer "+state.statusTicket,second.authorization)
        assertFalse(first.toString().contains(state.statusTicket))
        assertFalse(second.toString().contains(state.statusTicket))
        assertFalse(first.url.toString().contains(state.statusTicket))
    }

    @Test fun missingAdmissionMalformedReceiptAndRedirectNeverConfirmDeletion() = runBlocking {
        var calls=0
        val missing = DeletionProtocolTransport(endpoint,DeletionAppCheck{null},DeletionWire{calls++;response(202)})
        assertTrue(runCatching { missing.request("fixture-id-token",state.statusTicket) }.isFailure)
        assertEquals(0,calls)
        val wrong = listOf(response(302),response(503),response(200,"b".repeat(64),"COMPLETE"),
            response(200,phase="SUCCESS"),
            DeletionWireResponse(200,"text/html","no-store","<html>bad</html>"),
            DeletionWireResponse(200,"application/json","public",response(200).body),
            DeletionWireResponse(200,"application/json","no-store",response(200).body+"extra"),
            DeletionWireResponse(200,"application/json","no-store",JSONObject().put("jobId",job).put("phase","COMPLETE").put("uid","unexpected").toString()))
        for (reply in wrong) {
            val transport = DeletionProtocolTransport(endpoint,DeletionAppCheck{"fixture-app-check"},DeletionWire{reply})
            assertTrue(runCatching { transport.status(job,state.statusTicket) }.isFailure)
        }
    }
}
